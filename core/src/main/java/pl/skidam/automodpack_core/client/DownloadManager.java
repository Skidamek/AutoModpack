package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import pl.skidam.automodpack_core.protocol.LocalStorageException;
import pl.skidam.automodpack_core.protocol.NetUtils;
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
import pl.skidam.automodpack_core.utils.Throwables;
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

	// Matches DownloadClient.MAX_CONNECTIONS so every download worker owns one pipeline lane; big files never queue behind another on the same lane.
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
	// Workers run the short, blocking jobs: platform attempts, cache checks, and promotion. The wire itself is paced
	// separately (WirePacer), so a lane never idles behind a worker and a worker never blocks on the network.
	private final AtomicInteger laneCounter = new AtomicInteger();
	private final ThreadLocal<Integer> lane = ThreadLocal.withInitial(() -> Math.floorMod(laneCounter.getAndIncrement(), MAX_DOWNLOADS_IN_PROGRESS));
	private volatile WirePacer pacer = new WirePacer(MAX_DOWNLOADS_IN_PROGRESS, 1);

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
		this.pacer = new WirePacer(Math.max(1, transport.pipelineCapacity()), MAX_DOWNLOADS_IN_PROGRESS);
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

	// Guards the pump against re-entrancy: a request that settles inline inside a dispatch (a fail-fast submit) must not recurse the loop - the running iteration covers the freed slot.
	private boolean pumping;

	private synchronized void downloadNext() {
		// Drain, don't dribble: every settle and every finalize pumps the dispatch until the wire window or the worker
		// budget is full, so concurrency tracks the window as it grows instead of riding one file per settle.
		if (pumping) return;
		pumping = true;
		try {
			while (!queuedDownloads.isEmpty()) {
				if (!dispatchOne()) return;
			}
			stealIdleWork();
		} finally {
			pumping = false;
		}
	}

	/** Picks and submits one task; false means the queue is empty of dispatchable work or the budget is full and the next settle re-runs the dispatch. */
	private boolean dispatchOne() {
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
		if (pick == null) return false;

		QueuedDownload task = queuedDownloads.remove(pick.identity());
		if (task == null) return false; // The queue was cleared (cancel) between the snapshot and the removal.
		final FileInspection.HashPathPair key = pick.identity();
		final String activeDomain = pick.sourceDomain();

		boolean hostServed = activeDomain.equals(INTERNAL_CLIENT_SOURCE) && transport != null;
		if (hostServed) {
			if (!pacer.tryAcquire()) {
				// The wire window is full: put the task back; the next settle re-runs the dispatch.
				queuedDownloads.put(key, task);
				return false;
			}
		} else if (platformTasksInFlight() >= MAX_DOWNLOADS_IN_PROGRESS) {
			queuedDownloads.put(key, task);
			return false;
		}

		LOGGER.info("Queueing download for: {} {} {}", task.file, task.fileSize, activeDomain);

		CompletableFuture<Void> future = new CompletableFuture<>();
		DownloadData data = new DownloadData(future, task.file, activeDomain, task.fileSize);
		data.key = key;
		data.task = task;
		data.hostServed = hostServed;
		downloadsInProgress.put(key, data);
		if (cancelled || downloadExecutor.isShutdown()) {
			downloadsInProgress.remove(key);
			failedFiles.incrementAndGet();
			semaphore.release();
			return false;
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
			return false;
		} catch (RuntimeException error) {
			downloadsInProgress.remove(key);
			future.completeExceptionally(error);
			throw error;
		}
		stealIdleWork();
		return true;
	}

	/** Platform tasks occupy a worker for their whole blocking attempt; host tasks do not. */
	private long platformTasksInFlight() {
		return downloadsInProgress.values().stream().filter(data -> !data.hostServed).count();
	}

	// Files with no platform sources can still come from the attached host client; that is labelled as its own domain so the scheduler can weigh it like any other source.
	// Domains this task already burned its attempts on are withheld, so a retry dispatches to a different source instead of re-picking the same one.
	// A task whose platform budget is fully burned falls back to the host wire when one is attached.
	private List<String> candidateDomains(QueuedDownload task) {
		if (task.sources.isEmpty()) return List.of(INTERNAL_CLIENT_SOURCE);
		List<String> domains = task.sources.stream().map(source -> getDomainFromUrl(source.url())).toList();
		List<String> candidates = domains.stream().filter(domain -> task.domainFailures.getOrDefault(domain, 0) < MAX_DOWNLOAD_ATTEMPTS).toList();
		if (candidates.isEmpty() && transport != null) return List.of(INTERNAL_CLIENT_SOURCE);
		return candidates.isEmpty() ? domains : candidates;
	}

	/** The platform source for the picked domain, or null when its attempt budget is burned and the host wire takes over. */
	private DownloadSource platformSourceForDomain(QueuedDownload task, String domain) {
		int numberOfIndexes = task.sources.size();
		int chosenIndex = sourceIndexForDomain(task, domain);
		int sourceIndex = chosenIndex >= 0 ? chosenIndex : Math.min(task.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);
		DownloadSource source = (numberOfIndexes > sourceIndex) ? task.sources.get(sourceIndex) : null;
		boolean budgetLeft = task.attempts < MAX_DOWNLOAD_ATTEMPTS * numberOfIndexes;
		return source != null && budgetLeft ? source : null;
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

		try (FileCache cache = FileCache.open(dataLayout.fileCacheDirectory())) {
			if (ClientObjectStore.acquireVerified(storeFile, hashPathPair.hash(), task.fileSize, List.of(), cache, ClientObjectStore.CorruptObjectPolicy.EVICT_QUIETLY).present()) {
				totalBytesDownloaded.addAndGet(task.fileSize);
				// IMPORTANT: Do NOT add cached bytes to Speedometer.
				// It would fake a massive speed spike.

				releaseWindow(data);
				cleanupAndFinalize(hashPathPair, task, storeFile, true, false);
				return;
			}
			// DOWNLOAD REQUIRED. A corrupt object is never a cache hit.
			Path partial = preparePartial(hashPathPair, task);
			if (partial == null) {
				releaseWindow(data);
				cleanupAndFinalize(hashPathPair, task, storeFile, false, false);
				return;
			}
			refreshDeadLinkSources(hashPathPair.hash(), task);
			DownloadSource source = data.hostServed ? null : platformSourceForDomain(task, data.activeDomain);
			if (data.hostServed || source == null) {
				if (source == null && !data.hostServed) { // burned platform budget falls through to the host wire
					if (transport == null) {
						task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
						cleanupAndFinalize(hashPathPair, task, storeFile, false, false);
						return;
					}
					if (!pacer.tryAcquire()) { // window full: requeue, the next settle rediscovers this task
						downloadsInProgress.remove(hashPathPair);
						queuedDownloads.put(hashPathPair, task);
						activeTemporaryFiles.remove(hashPathPair);
						return;
					}
					data.hostServed = true;
				}
				submitHostItems(hashPathPair, task, data, partial);
			} else {
				boolean success = attemptPlatformDownload(hashPathPair, task, data, source, partial);
				promotePlatformDownload(hashPathPair, task, storeFile, cache, partial, success);
			}
		} catch (InterruptedException e) {
			task.lastFailureCategory = FailureCategory.CANCELLED;
			releaseWindow(data);
			cleanupAndFinalize(hashPathPair, task, storeFile, false, true);
		} catch (Exception e) {
			if (cancelled || Thread.currentThread().isInterrupted()) {
				task.lastFailureCategory = FailureCategory.CANCELLED;
				releaseWindow(data);
				cleanupAndFinalize(hashPathPair, task, storeFile, false, true);
			} else {
				if (task.lastFailureCategory == null) task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
				LOGGER.warn("Unexpected error processing {}", task.file, e);
				releaseWindow(data);
				cleanupAndFinalize(hashPathPair, task, storeFile, false, false);
			}
		}
	}

	/** Returns a wire-window credit taken for a host task that ended before submitting any request. */
	private void releaseWindow(DownloadData data) {
		if (data.hostServed) pacer.release();
	}

	/** Creates the task's one partial temp; null with the failure recorded means the attempt cannot start. */
	private Path preparePartial(FileInspection.HashPathPair hashPathPair, QueuedDownload task) {
		try {
			if (task.partialFile == null) {
				Path stagingDirectory = dataLayout.stagingDirectory();
				Files.createDirectories(stagingDirectory);
				task.partialFile = Files.createTempFile(stagingDirectory, "." + hashPathPair.hash() + ".", ".tmp");
			}
			activeTemporaryFiles.put(hashPathPair, task.partialFile);
			return task.partialFile;
		} catch (IOException e) {
			task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
			LOGGER.warn("Failed to create temporary CAS object {}", hashPathPair.hash(), e);
			return null;
		}
	}

	/** The blocking platform path: one HTTP download from the picked source. The wire pacer is not involved. */
	private boolean attemptPlatformDownload(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, DownloadSource source, Path partial) throws InterruptedException {
		refreshDeadLinkSources(hashPathPair.hash(), task);
		long attemptStart = System.nanoTime();
		AtomicLong attemptBytes = new AtomicLong(0);
		// One hook for everything the written bytes mean: global progress, display speed, this attempt's sample and the in-flight backlog left for the scheduler.
		IntConsumer progressAction = bytes -> {
			updateNetworkProgress(bytes);
			attemptBytes.addAndGet(bytes);
			data.remainingBytes.addAndGet(-bytes);
		};
		try {
			httpDownloader.download(source, partial, progressAction);
		} catch (LocalStorageException e) {
			task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
			LOGGER.warn("Failed to write temporary CAS object {}", hashPathPair.hash(), e);
			deletePartial(task);
			return false;
		} catch (HttpFileDownloader.HttpStatusException e) {
			task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
			if (isDeadPlatformLink(source, e)) markDeadPlatformLink(hashPathPair.hash(), task);
			else if (source.provider() == DownloadSource.Provider.CURSEFORGE && e.statusCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
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
			if (attemptBytes.get() > 0) scheduler.report(data.activeDomain, attemptBytes.get(), System.nanoTime() - attemptStart);
		}
		return true;
	}

	private void promotePlatformDownload(FileInspection.HashPathPair hashPathPair, QueuedDownload task, Path storeFile, FileCache cache, Path partial, boolean downloaded) {
		if (downloaded) promoteHostFile(hashPathPair, task, partial, cache);
		cleanupAndFinalize(hashPathPair, task, storeFile, downloaded, false);
	}

	/** The host path: the first segment rides the dispatch credit, every further chunk waits for a free window slot; the barrier finalizes once all of them are in. */
	private void submitHostItems(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, Path partial) {
		task.hostError = null;
		task.stealCursor = task.fileSize;
		long offset = hostResumeOffset(task);
		data.hostOffset = offset;
		if (offset >= task.fileSize) {
			// A complete-sized partial skips the network; promotion judges it for free.
			releaseWindow(data);
			finishHostFile(hashPathPair, task, data, partial);
			return;
		}
		task.pendingItems = 1;
		submitHostItem(hashPathPair, task, data, partial, offset, Math.min(offset + (long) NetUtils.DEFAULT_CHUNK_SIZE, task.fileSize) - 1, lane.get());
		stealIdleWork();
	}

	/** The byte offset a host attempt resumes from: the partial's size while the prefix is valid, else a fresh start. */
	private long hostResumeOffset(QueuedDownload task) {
		try {
			if (task.partialFile == null) return 0;
			long size = Files.size(task.partialFile);
			if (!task.resumeValid || size > task.fileSize) {
				LOGGER.warn("Stored partial for CAS object {} is not a valid prefix; restarting from zero", task.file.getFileName());
				deletePartial(task);
				return 0;
			}
			return size;
		} catch (IOException e) {
			LOGGER.warn("Failed to inspect the partial of CAS object {}; restarting from zero", task.file.getFileName(), e);
			deletePartial(task);
			return 0;
		}
	}

	/** One request of the task's file on one lane, covering [offset, endInclusive]; its settle feeds the pacer and ticks the task's barrier down. */
	private void submitHostItem(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, Path partial, long offset, long endInclusive, int lane) {
		AtomicLong itemBytes = new AtomicLong(0);
		long itemStart = System.nanoTime();
		IntConsumer progressAction = bytes -> {
			updateNetworkProgress(bytes);
			itemBytes.addAndGet(bytes);
			data.remainingBytes.addAndGet(-bytes);
		};
		CompletableFuture<Path> future;
		try {
			future = transport.downloadFile(hashPathPair.hash().getBytes(StandardCharsets.UTF_8), partial, offset, endInclusive, progressAction, lane);
		} catch (Throwable error) {
			// The submit never produced an item: return its window credit and tick the barrier down, or the task waits for a settle that never comes.
			if (task.hostError == null) task.hostError = Throwables.unwrap(error);
			pacer.settle(true, 0, 0, lane);
			onHostItemSettled(hashPathPair, task, data, partial);
			return;
		}
		future.whenComplete((path, error) -> {
			if (error != null && task.hostError == null) task.hostError = Throwables.unwrap(error);
			long settled = System.nanoTime() - itemStart;
			pacer.settle(error != null, itemBytes.get(), settled, lane);
			if (itemBytes.get() > 0) scheduler.report(INTERNAL_CLIENT_SOURCE, itemBytes.get(), settled);
			onHostItemSettled(hashPathPair, task, data, partial);
		});
	}

	/**
	 * Runs on the lane's reader thread: barrier bookkeeping only, then the window refill. The task is done only once every chunk beyond the first is taken - a partial barrier would promote a hole-riddled file - or once
	 * it has failed.
	 */
	private void onHostItemSettled(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, Path partial) {
		boolean done;
		synchronized (task) {
			done = --task.pendingItems == 0 && (task.hostError != null || task.stealCursor <= data.hostOffset + (long) NetUtils.DEFAULT_CHUNK_SIZE);
		}
		if (done) {
			Throwable error = task.hostError;
			downloadExecutor.execute(() -> finishHostFile(hashPathPair, task, data, partial, error));
		} else {
			downloadNext();
		}
	}

	/** The task barrier: every item settled, so promotion judges the assembled partial whatever holes a crash left. */
	private void finishHostFile(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, Path partial) {
		finishHostFile(hashPathPair, task, data, partial, null);
	}

	private void finishHostFile(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, Path partial, Throwable error) {
		if (error != null) {
			if (cancelled || error instanceof InterruptedException) {
				task.lastFailureCategory = FailureCategory.CANCELLED;
				deletePartial(task); // the writers died with the aborted lanes; the partial is a hole-riddled relic
			} else if (error instanceof StaleRangeException) {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				deletePartial(task);
			} else if (error instanceof LocalStorageException) {
				task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
				deletePartial(task);
			} else {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				LOGGER.warn("Remote source failed for CAS object {}", hashPathPair.hash(), error);
			}
			cleanupAndFinalize(hashPathPair, task, dataLayout.objectFile(hashPathPair.hash()), false, false);
			return;
		}
		try (FileCache cache = FileCache.open(dataLayout.fileCacheDirectory())) {
			boolean promoted = promoteHostFile(hashPathPair, task, partial, cache);
			cleanupAndFinalize(hashPathPair, task, dataLayout.objectFile(hashPathPair.hash()), promoted, false);
		} catch (IOException e) {
			task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
			cleanupAndFinalize(hashPathPair, task, dataLayout.objectFile(hashPathPair.hash()), false, false);
		}
	}

	/** Hashes the assembled partial into the CAS store; false with the failure recorded means the download is retried. */
	private boolean promoteHostFile(FileInspection.HashPathPair hashPathPair, QueuedDownload task, Path partial, FileCache cache) {
		try {
			VerifiedFileTransfer.promoteAtomic(partial, dataLayout.objectFile(hashPathPair.hash()), task.fileSize, hashPathPair.hash(), cache);
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

	/** The queue is drained or the budget full but the window has room: hand idle slots to the tails of in-flight host files, one exact chunk per take. */
	private synchronized void stealIdleWork() {
		long chunk = NetUtils.DEFAULT_CHUNK_SIZE;
		while (pacer.tryAcquire()) {
			boolean stole = false;
			for (DownloadData data : downloadsInProgress.values()) {
				QueuedDownload task = data.task;
				if (data.hostOffset < 0 || task.hostError != null) continue;
				// The task lock decides take vs finish, exactly as the barrier's done check reads it: once the cursor
				// sits at the first chunk there is nothing left to take, so a finished task is never joined.
				synchronized (task) {
					long floor = data.hostOffset + chunk; // never take the first chunk; the streamer owns it
					long stealFrom = task.stealCursor - chunk;
					if (stealFrom < floor) continue;
					Path partial = task.partialFile;
					if (partial == null) continue;
					task.stealCursor = stealFrom;
					task.resumeValid = false; // a positioned take makes the partial's size meaningless for resume
					task.pendingItems++;
					int lane = Math.floorMod(laneCounter.getAndIncrement(), MAX_DOWNLOADS_IN_PROGRESS);
					LOGGER.debug("[download] lane {} takes bytes {}..{} of {}", lane, stealFrom, stealFrom + chunk - 1, task.file.getFileName());
					submitHostItem(data.key, task, data, partial, stealFrom, stealFrom + chunk - 1, lane);
				}
				stole = true;
				break;
			}
			if (!stole) {
				pacer.release(); // nothing left to take
				return;
			}
		}
	}

	/** Deletes the task's partial temp and forgets it; the next attempt, if any, starts from zero. */
	private static void deletePartial(QueuedDownload task) {
		if (task.partialFile == null) return;
		try {
			Files.deleteIfExists(task.partialFile);
		} catch (IOException ignored) {
		}
		task.partialFile = null;
		task.resumeValid = true;
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
			if (downloadsInProgress.isEmpty() && queuedDownloads.isEmpty()) LOGGER.info("[download] {} pacer summary: {}", task.file.getFileName(), pacer.summary());
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
		// Only partials without a live writer are swept here; an in-flight attempt is interrupted first and deletes its
		// own partial when its task ends, so the sweep never unlinks a file a writer still holds.
		activeTemporaryFiles.forEach((key, path) -> {
			if (downloadsInProgress.containsKey(key)) return;
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
		/** Unsettled host items of the current attempt; the last one to settle finalizes the task. */
		public int pendingItems;
		/** The tail boundary the next idle-lane steal may take; the streamer always keeps at least one chunk. */
		public long stealCursor;
		/** False once a positioned steal write made the partial's size meaningless for resume. */
		public boolean resumeValid = true;
		/** The first failure of the current attempt; the barrier reports it. */
		public volatile Throwable hostError;

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
		public FileInspection.HashPathPair key;
		public QueuedDownload task;
		/** True when the wire pacer owns this task's transfer; a window credit is outstanding until its first settle. */
		public boolean hostServed;
		/** The streamer's range start, or -1 before the host path submitted anything. */
		public long hostOffset = -1;

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
