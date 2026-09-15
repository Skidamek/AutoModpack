package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import pl.skidam.automodpack_core.protocol.LocalStorageException;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.protocol.StaleRangeException;
import pl.skidam.automodpack_core.screen.DownloadView;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.DownloadScheduler;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;

public class DownloadManager implements DownloadView {

	public enum FailureCategory {
		REMOTE_SOURCE,
		LOCAL_STORAGE,
		CANCELLED
	}

	/** Finished acquisitions so far: files acquired successfully, then files failed for good. Survives {@link #cancelAllAndShutdown()}. */
	public record AcquisitionProgress(long acquired, long failed) {}

	private static final int MAX_DOWNLOADS_IN_PROGRESS = 5;
	private static final int MAX_DOWNLOAD_ATTEMPTS = 2;
	// Domain label for transfers served by the attached AutoModpack host client instead of a remote platform source.
	private static final String INTERNAL_CLIENT_SOURCE = "internal_client";

	private final ExecutorService downloadExecutor;

	private final HttpFileDownloader httpDownloader = new HttpFileDownloader();
	private PackTransport transport = null;
	// Owns the batched platform-metadata refetch of this run: concurrent dead links share the bulk API calls.
	private final FetchManager metadataFetcher;

	private volatile boolean cancelled = false;

	private final Map<FileInspection.HashPathPair, QueuedDownload> queuedDownloads = new ConcurrentHashMap<>();
	private final Map<FileInspection.HashPathPair, DownloadData> downloadsInProgress = new ConcurrentHashMap<>();
	private final Map<FileInspection.HashPathPair, Path> activeTemporaryFiles = new ConcurrentHashMap<>();

	// The acquisition summary outlives cancelAllAndShutdown, unlike downloadedCount which resets for the stage line;
	// each task records at most once per run, so plain counters mirror what the recorded results map produced.
	private final AtomicLong acquiredFiles = new AtomicLong(0);
	private final AtomicLong failedFiles = new AtomicLong(0);

	private final DownloadScheduler scheduler = new DownloadScheduler();

	private final AtomicLong totalBytesToDownload = new AtomicLong(0);
	private final AtomicLong totalBytesDownloaded = new AtomicLong(0);
	private int totalFilesAdded = 0;
	private int enqueueSequence = 0;
	private int downloadedCount = 0;

	private final Semaphore semaphore = new Semaphore(0);
	private final Speedometer speedometer = new Speedometer();
	private final DataRootResolver.Layout dataLayout;

	public DownloadManager(long bytesToDownload, DataRootResolver.Layout dataLayout, PlatformCache platformCache) {
		this.totalBytesToDownload.set(bytesToDownload);
		this.speedometer.setExpectedBytes(bytesToDownload);
		this.dataLayout = Objects.requireNonNull(dataLayout, "dataLayout");
		this.downloadExecutor = Executors.newFixedThreadPool(MAX_DOWNLOADS_IN_PROGRESS,
				new CustomThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build());
		this.metadataFetcher = new FetchManager(List.of(), Objects.requireNonNull(platformCache, "platformCache"));
	}

	public void attachTransport(PackTransport transport) {
		this.transport = transport;
	}

	public synchronized void download(Path file, String sha1, String murmur, String fileType, List<DownloadSource> sources, long fileSize, Runnable successCallback, Runnable failureCallback) {
		download(file, sha1, murmur, fileType, sources, fileSize, successCallback, ignored -> failureCallback.run());
	}

	public synchronized void download(Path file, String sha1, String murmur, String fileType, List<DownloadSource> sources, long fileSize, Runnable successCallback,
			Consumer<FailureCategory> failureCallback) {
		FileInspection.HashPathPair hashPathPair = new FileInspection.HashPathPair(sha1, file);
		if (queuedDownloads.containsKey(hashPathPair)) return;

		QueuedDownload task = new QueuedDownload(file, new ArrayList<>(sources), murmur, fileType, fileSize, enqueueSequence++, 0, successCallback, failureCallback);
		queuedDownloads.put(hashPathPair, task);
		totalFilesAdded++;
		downloadNext();
	}

	private synchronized void downloadNext() {
		if (downloadsInProgress.size() >= MAX_DOWNLOADS_IN_PROGRESS || queuedDownloads.isEmpty()) return;

		// SCHEDULING: the largest queued file first (ties in enqueue order), then among its candidate domains the one
		// whose measured speed makes backlog-plus-this-file finish soonest. Domains this task already burned its
		// attempts on are withheld (candidateDomains); dead links are handled at attempt time.
		List<Map.Entry<FileInspection.HashPathPair, QueuedDownload>> entries = new ArrayList<>(queuedDownloads.entrySet());
		entries.sort(Comparator.comparingInt(entry -> entry.getValue().seq));
		List<DownloadScheduler.QueuedFile<FileInspection.HashPathPair>> queue = new ArrayList<>(entries.size());
		for (Map.Entry<FileInspection.HashPathPair, QueuedDownload> entry : entries) queue.add(new DownloadScheduler.QueuedFile<>(entry.getKey(), entry.getValue().fileSize, candidateDomains(entry.getValue())));
		Map<String, Long> inFlightBacklog = new HashMap<>();
		for (DownloadData data : downloadsInProgress.values()) inFlightBacklog.merge(data.activeDomain, Math.max(0, data.remainingBytes.get()), Long::sum);

		DownloadScheduler.Pick<FileInspection.HashPathPair> pick = scheduler.pick(queue, inFlightBacklog);
		if (pick == null) return;

		QueuedDownload task = queuedDownloads.remove(pick.identity());
		if (task == null) return; // The queue was cleared (cancel) between the snapshot and the removal.
		final FileInspection.HashPathPair key = pick.identity();
		final String activeDomain = pick.sourceDomain();

		LOGGER.info("Queueing download for: {} {} {}", task.file, task.fileSize, activeDomain);

		CompletableFuture<Void> future = new CompletableFuture<>();
		DownloadData data = new DownloadData(future, task.file, activeDomain, task.fileSize);
		downloadsInProgress.put(key, data);
		if (cancelled || downloadExecutor.isShutdown()) {
			downloadsInProgress.remove(key);
			failedFiles.incrementAndGet();
			semaphore.release();
			return;
		}
		try {
			downloadExecutor.execute(() -> {
				try {
					processDownloadTask(key, task, data);
					future.complete(null);
				} catch (Throwable error) {
					LOGGER.error("Fatal error executing download task for {}", task.file.getFileName(), error);
					future.completeExceptionally(error);
				}
			});
		} catch (RejectedExecutionException error) {
			downloadsInProgress.remove(key);
			failedFiles.incrementAndGet();
			semaphore.release();
			future.completeExceptionally(error);
		} catch (RuntimeException error) {
			downloadsInProgress.remove(key);
			future.completeExceptionally(error);
			throw error;
		}
	}

	// Files with no platform sources can still come from the attached host client; that is labelled as its own domain so the scheduler can weigh it like any other source.
	// Domains this task already burned its attempts on are withheld, so a retry dispatches to a different source instead of re-picking the same one.
	private List<String> candidateDomains(QueuedDownload task) {
		if (task.sources.isEmpty()) return List.of(INTERNAL_CLIENT_SOURCE);
		List<String> domains = task.sources.stream().map(source -> getDomainFromUrl(source.url())).toList();
		List<String> candidates = domains.stream().filter(domain -> task.domainFailures.getOrDefault(domain, 0) < MAX_DOWNLOAD_ATTEMPTS).toList();
		return candidates.isEmpty() ? domains : candidates;
	}

	private int sourceIndexForDomain(QueuedDownload task, String domain) {
		for (int i = 0; i < task.sources.size(); i++) {
			if (getDomainFromUrl(task.sources.get(i).url()).equals(domain)) return i;
		}
		return -1;
	}

	private String getDomainFromUrl(String url) {
		if (url == null) return "unknown";
		try {
			int protocolEnd = url.indexOf("://");
			String noProtocol = (protocolEnd > -1) ? url.substring(protocolEnd + 3) : url;
			int slash = noProtocol.indexOf('/');
			return (slash > -1) ? noProtocol.substring(0, slash) : noProtocol;
		} catch (Exception e) {
			return "unknown";
		}
	}

	private void processDownloadTask(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data) {
		Path storeFile = dataLayout.objectFile(hashPathPair.hash());
		boolean success = false;
		boolean interrupted = false;

		try (FileCache cache = FileCache.open(dataLayout.fileCacheDirectory())) {
			if (ClientObjectStore.acquireVerified(storeFile, hashPathPair.hash(), task.fileSize, List.of(), cache, ClientObjectStore.CorruptObjectPolicy.EVICT_QUIETLY).present()) {
				totalBytesDownloaded.addAndGet(task.fileSize);
				// IMPORTANT: Do NOT add cached bytes to Speedometer.
				// It would fake a massive speed spike.

				success = true;
			} else {
				// DOWNLOAD REQUIRED. A corrupt object is never a cache hit.
				success = attemptDownload(hashPathPair, task, data, storeFile, cache);
			}
		} catch (InterruptedException e) {
			interrupted = true;
			task.lastFailureCategory = FailureCategory.CANCELLED;
		} catch (Exception e) {
			if (cancelled || Thread.currentThread().isInterrupted()) {
				interrupted = true;
				task.lastFailureCategory = FailureCategory.CANCELLED;
			} else {
				if (task.lastFailureCategory == null) task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
				LOGGER.warn("Unexpected error processing {}", task.file, e);
			}
		} finally {
			cleanupAndFinalize(hashPathPair, task, storeFile, success, interrupted);
		}
	}

	private boolean attemptDownload(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, Path storeFile, FileCache cache) throws InterruptedException {
		refreshDeadLinkSources(hashPathPair.hash(), task);
		int numberOfIndexes = task.sources.size();
		// The scheduler chose the domain for this dispatch; the attempts-based rotation only takes over when that domain
		// is gone (fresh metadata replaced the source list, which also reset the attempts).
		int chosenIndex = sourceIndexForDomain(task, data.activeDomain);
		int sourceIndex = chosenIndex >= 0 ? chosenIndex : Math.min(task.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);
		DownloadSource source = (numberOfIndexes > sourceIndex) ? task.sources.get(sourceIndex) : null;
		Path tempStoreFile = null;

		try {
			// One partial temp per task: it survives failed attempts as the resume prefix, and its activeTemporaryFiles
			// registration spans the whole task so cancel still sweeps it even between attempts.
			if (task.partialFile == null) {
				Path stagingDirectory = dataLayout.stagingDirectory();
				Files.createDirectories(stagingDirectory);
				task.partialFile = Files.createTempFile(stagingDirectory, "." + hashPathPair.hash() + ".", ".tmp");
			}
			tempStoreFile = task.partialFile;
			activeTemporaryFiles.put(hashPathPair, tempStoreFile);
		} catch (IOException e) {
			task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
			LOGGER.warn("Failed to create temporary CAS object {}", hashPathPair.hash(), e);
			return false;
		}

		long attemptStart = System.nanoTime();
		AtomicLong attemptBytes = new AtomicLong(0);
		// One hook for everything the written bytes mean: global progress, display speed, this attempt's sample and the in-flight backlog left for the scheduler.
		IntConsumer progressAction = bytes -> {
			updateNetworkProgress(bytes);
			attemptBytes.addAndGet(bytes);
			data.remainingBytes.addAndGet(-bytes);
		};
		boolean platformTransfer = source != null && task.attempts < MAX_DOWNLOAD_ATTEMPTS * numberOfIndexes;
		try {
			if (platformTransfer) {
				httpDownloader.download(source, tempStoreFile, progressAction);
			} else if (transport != null) {
				hostDownloadFile(hashPathPair, task, tempStoreFile, progressAction);
			} else {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				return false;
			}
		} catch (LocalStorageException e) {
			task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
			LOGGER.warn("Failed to write temporary CAS object {}", hashPathPair.hash(), e);
			deletePartial(task);
			return false;
		} catch (HttpFileDownloader.HttpStatusException e) {
			task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
			if (isDeadPlatformLink(source, e)) markDeadPlatformLink(hashPathPair.hash(), task);
			else if (source != null && source.provider() == DownloadSource.Provider.CURSEFORGE && e.statusCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
				LOGGER.warn("CurseForge rejected the download API key with HTTP 401; trying the next source");
				task.domainFailures.merge(data.activeDomain, MAX_DOWNLOAD_ATTEMPTS, Integer::sum);
			}
			return false;
		} catch (StaleRangeException e) {
			// The partial is beyond the served object's end: worthless, so the next attempt starts clean.
			task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
			LOGGER.warn("Stored partial for CAS object {} is past the served object's end; restarting from zero", hashPathPair.hash());
			deletePartial(task);
			return false;
		} catch (IOException e) {
			if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("Download of CAS object " + hashPathPair.hash() + " was cancelled");
			task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
			LOGGER.warn("Remote source failed for CAS object {}", hashPathPair.hash(), e);
			return false;
		} finally {
			// Every byte that arrived is real bandwidth data for the path it actually travelled, whatever happened to the transfer.
			if (attemptBytes.get() > 0) scheduler.report(platformTransfer ? data.activeDomain : INTERNAL_CLIENT_SOURCE, attemptBytes.get(), System.nanoTime() - attemptStart);
		}

		try {
			VerifiedFileTransfer.promoteAtomic(tempStoreFile, storeFile, task.fileSize, hashPathPair.hash(), cache);
		} catch (VerifiedFileTransfer.VerificationMismatchException e) {
			task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
			LOGGER.warn("Size or hash mismatch for downloaded file {}", task.file.getFileName());
			deletePartial(task);
			return false;
		} catch (IOException e) {
			task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
			LOGGER.warn("Failed to persist verified CAS object {}", hashPathPair.hash(), e);
			deletePartial(task);
			return false;
		}
		task.partialFile = null;
		task.lastFailureCategory = null;
		return true;
	}

	/** Deletes the task's partial temp and forgets it; the next attempt, if any, starts from zero. */
	private static void deletePartial(QueuedDownload task) {
		if (task.partialFile == null) return;
		try {
			Files.deleteIfExists(task.partialFile);
		} catch (IOException ignored) {
		}
		task.partialFile = null;
	}

	private static boolean isDeadPlatformLink(DownloadSource source, HttpFileDownloader.HttpStatusException e) {
		return source != null && (e.statusCode() == HttpURLConnection.HTTP_NOT_FOUND || e.statusCode() == HttpURLConnection.HTTP_GONE);
	}

	private void markDeadPlatformLink(String sha1, QueuedDownload task) {
		if (!metadataFetcher.markDeadPlatformLink(sha1, task.murmur, task.fileType)) return;
		task.needsMetadataRefetch = true;
		LOGGER.warn("Dead platform link for CAS object {}; its metadata will be refetched before the next attempt", sha1);
	}

	private void refreshDeadLinkSources(String sha1, QueuedDownload task) {
		if (!task.needsMetadataRefetch) return;
		List<DownloadSource> fresh = metadataFetcher.awaitMetadataRefetch(sha1);
		task.needsMetadataRefetch = false;
		if (fresh.isEmpty()) return;
		task.sources.clear();
		task.sources.addAll(fresh);
		task.attempts = 0;
		task.domainFailures.clear();
	}

	private void cleanupAndFinalize(FileInspection.HashPathPair key, QueuedDownload task, Path storeFile, boolean success, boolean interrupted) {
		DownloadData data = downloadsInProgress.remove(key);
		// A failed attempt counts against the domain that served it, so the retry dispatches elsewhere before the
		// attempts budget forces the task to give up.
		if (data != null && !success && !interrupted) task.domainFailures.merge(data.activeDomain, 1, Integer::sum);

		try {
			if (success) {
				activeTemporaryFiles.remove(key);
				downloadedCount++;
				acquiredFiles.incrementAndGet();
				LOGGER.info("Acquired CAS object {} for {}", storeFile.getFileName(), task.file.getFileName());
				try {
					task.successCallback.run();
				} finally {
					semaphore.release();
				}
			} else {
				// A task that ended for good stops tracking its partial; a requeued one keeps it for the next attempt.
				if (handleRetry(key, task, interrupted)) activeTemporaryFiles.remove(key);
			}
		} finally {
			if (!interrupted && !cancelled && !downloadExecutor.isShutdown()) downloadNext();
		}
	}

	/** Requeues the task for another attempt; false means it was requeued, true means the task ended for good. */
	private boolean handleRetry(FileInspection.HashPathPair key, QueuedDownload task, boolean interrupted) {
		if (interrupted || cancelled) {
			failedFiles.incrementAndGet();
			semaphore.release();
			return true;
		}
		if (task.lastFailureCategory != FailureCategory.LOCAL_STORAGE && task.attempts < (task.sources.size() + 1) * MAX_DOWNLOAD_ATTEMPTS) {
			LOGGER.warn("Retrying download: {}", task.file.getFileName());
			task.attempts++;
			queuedDownloads.put(key, task);
			return false;
		}
		FailureCategory category = task.lastFailureCategory == null ? FailureCategory.REMOTE_SOURCE : task.lastFailureCategory;
		failedFiles.incrementAndGet();
		LOGGER.error("Permanently failed to download {} ({})", task.file.getFileName(), category);
		deletePartial(task);
		try {
			task.failureCallback.accept(category);
		} finally {
			semaphore.release();
		}
		return true;
	}

	/**
	 * Host fetch with resume: the partial's length is the offset. An oversized partial starts over, a complete-sized
	 * one skips the network and lets promotion judge it for free, and a range past the object's end deletes it.
	 */
	private void hostDownloadFile(FileInspection.HashPathPair hashPathPair, QueuedDownload task, Path partial, IntConsumer progressAction)
			throws IOException, InterruptedException {
		long offset = 0;
		if (Files.exists(partial)) {
			offset = Files.size(partial);
			if (offset > task.fileSize) {
				deletePartial(task);
				offset = 0;
			} else if (offset == task.fileSize) {
				return;
			}
		}
		var future = transport.downloadFile(hashPathPair.hash().getBytes(StandardCharsets.UTF_8), partial, offset, progressAction);
		try {
			future.get();
		} catch (InterruptedException e) {
			future.cancel(true);
			transport.abortTransfers();
			throw e;
		} catch (CancellationException e) {
			future.cancel(true);
			transport.abortTransfers();
			throw new InterruptedException("AutoModpack host download was cancelled");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof LocalStorageException localStorageException) throw localStorageException;
			if (cause instanceof StaleRangeException staleRange) throw staleRange;
			if (cause instanceof InterruptedException) throw new InterruptedException("AutoModpack host download was interrupted");
			throw new IOException("AutoModpack host download failed", cause);
		}
	}

	private void updateNetworkProgress(long bytes) {
		totalBytesDownloaded.addAndGet(bytes);
		speedometer.addBytes(bytes);
	}

	public void joinAll() throws InterruptedException {
		semaphore.acquire(totalFilesAdded);
		if (downloadExecutor.isShutdown()) throw new InterruptedException();
		semaphore.release(totalFilesAdded);
	}

	// --- UI Helpers ---

	public long getDownloadSpeed() {
		return speedometer.getSpeed();
	}

	public long getETA() {
		return speedometer.getETA();
	}

	public double getPrecisePercentage() {
		long total = totalBytesToDownload.get();
		if (total == 0) return 0.0;
		double pc = (double) totalBytesDownloaded.get() * 100.0 / total;
		return Math.max(0.0, Math.min(100.0, pc));
	}

	public String getStage() {
		return downloadedCount + "/" + totalFilesAdded;
	}

	public boolean isRunning() {
		return !downloadExecutor.isShutdown();
	}

	/** Stops the pool after every queued file has finished. Does not mark the run cancelled. */
	public void finish() {
		downloadExecutor.shutdown();
	}

	public void cancelAllAndShutdown() {
		cancelled = true;
		if (transport != null) transport.abortTransfers();
		LOGGER.info("Cancelling the download run: {} queued, {} in-flight", queuedDownloads.size(), downloadsInProgress.size());
		queuedDownloads.clear();
		downloadsInProgress.forEach((k, v) -> v.future.cancel(true));
		activeTemporaryFiles.values().forEach(path -> {
			try {
				Files.deleteIfExists(path);
			} catch (IOException ignored) {
			}
		});
		activeTemporaryFiles.clear();
		semaphore.release(totalFilesAdded);
		downloadsInProgress.clear();
		downloadedCount = 0;
		downloadExecutor.shutdown();
	}

	/** Snapshot of finished acquisitions, what the acquisition summary line renders. */
	@Override
	public long acquired() {
		return acquisitionProgress().acquired();
	}

	@Override
	public long failed() {
		return acquisitionProgress().failed();
	}

	@Override
	public List<String> downloadingFileNames() {
		synchronized (downloadsInProgress) {
			return downloadsInProgress.values().stream().map(DownloadData::getFileName).toList();
		}
	}

	public AcquisitionProgress acquisitionProgress() {
		return new AcquisitionProgress(acquiredFiles.get(), failedFiles.get());
	}

	public boolean isCancelled() {
		return cancelled;
	}

	// --- Inner Classes ---

	public static class QueuedDownload {
		public final Path file;
		public final List<DownloadSource> sources;
		public final String murmur;
		public final String fileType;
		public final long fileSize;
		public final int seq;
		public final Map<String, Integer> domainFailures = new HashMap<>();
		public int attempts;
		public final Runnable successCallback;
		public final Consumer<FailureCategory> failureCallback;
		public FailureCategory lastFailureCategory;
		public boolean needsMetadataRefetch;
		/** The one partial temp of the whole task: kept across failed attempts as the resume prefix, deleted at its ends. */
		public Path partialFile;

		public QueuedDownload(Path f, List<DownloadSource> sources, String murmur, String fileType, long size, int seq, int a, Runnable s, Consumer<FailureCategory> fa) {
			file = f;
			this.sources = sources;
			this.murmur = murmur;
			this.fileType = fileType;
			fileSize = size;
			this.seq = seq;
			attempts = a;
			successCallback = s;
			failureCallback = fa;
		}
	}

	public static class DownloadData {
		public CompletableFuture<Void> future;
		public Path file;
		public String activeDomain;
		public long fileSize;
		public final AtomicLong remainingBytes;

		DownloadData(CompletableFuture<Void> f, Path p, String d, long s) {
			future = f;
			file = p;
			activeDomain = d;
			fileSize = s;
			remainingBytes = new AtomicLong(s);
		}

		public String getFileName() {
			return file.getFileName().toString();
		}
	}
}
