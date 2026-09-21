package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.*;
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
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.protocol.StaleRangeException;
import pl.skidam.automodpack_core.protocol.WireTrace;
import pl.skidam.automodpack_core.protocol.WireWindowFullException;
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

	private static final int MAX_DOWNLOAD_ATTEMPTS = 2;
	// Platform attempts are blocking whole-file HTTP pulls, unrelated to the host wire's lane count this used to be
	// welded to: five concurrent CDN downloads saturate any home link while bounding the parallelism any one CDN sees.
	private static final int PLATFORM_WORKERS = 5;
	private static final int HTTP_UNAUTHORIZED = 401;
	private static final int HTTP_NOT_FOUND = 404;
	private static final int HTTP_GONE = 410;
	// Domain label for transfers served by the attached AutoModpack host client instead of a remote platform source.
	private static final String INTERNAL_CLIENT_SOURCE = "internal_client";

	// Workers run the short, blocking jobs - platform attempts, cache checks, promotion - while host transfers
	// finalize on their transport future's callback, so a worker never blocks on the network.
	private final ExecutorService downloadExecutor;

	private final HttpFileDownloader httpDownloader = new HttpFileDownloader();
	private PackTransport transport = null;
	// Owns the batched platform-metadata refetch of this run: concurrent dead links share the bulk API calls.
	private final FetchManager metadataFetcher;

	private volatile boolean cancelled = false;

	private final Map<FileInspection.HashPathPair, QueuedDownload> queuedDownloads = new ConcurrentHashMap<>();
	// Dispatch order: largest file first, enqueue order breaks ties - a persistent queue, so ordering costs O(log N) per
	// insert/poll instead of a full copy and sort per dispatch. Mutated only under the manager monitor, like the map.
	private final PriorityQueue<QueuedDownload> dispatchOrder = new PriorityQueue<>((first, second) -> {
		int bySize = Long.compare(second.fileSize, first.fileSize);
		return bySize != 0 ? bySize : Integer.compare(first.seq, second.seq);
	});
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
	private final AtomicInteger downloadedCount = new AtomicInteger();

	private final Semaphore semaphore = new Semaphore(0);
	private final Speedometer speedometer = new Speedometer();
	private final DataRootResolver.Layout dataLayout;

	public DownloadManager(long bytesToDownload, DataRootResolver.Layout dataLayout, PlatformCache platformCache) {
		this.totalBytesToDownload.set(bytesToDownload);
		this.speedometer.setExpectedBytes(bytesToDownload);
		this.dataLayout = Objects.requireNonNull(dataLayout, "dataLayout");
		this.downloadExecutor = Executors.newFixedThreadPool(PLATFORM_WORKERS,
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
		task.key = hashPathPair;
		queuedDownloads.put(hashPathPair, task);
		dispatchOrder.add(task);
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
		} finally {
			pumping = false;
		}
	}

	/** Picks and submits one task; false means the queue is empty of dispatchable work or the budget is full and the next settle re-runs the dispatch. */
	private boolean dispatchOne() {
		// SCHEDULING: the queue is already in dispatch order (largest first, ties in enqueue order); among the first
		// dispatchable task's candidate domains chooseDomain picks the one whose measured speed makes
		// backlog-plus-this-file finish soonest. Domains a task already burned its attempts on are withheld
		// (candidateDomains); dead links are handled at attempt time.
		Map<String, Long> inFlightBacklog = new HashMap<>();
		for (DownloadData data : downloadsInProgress.values()) inFlightBacklog.merge(data.activeDomain, Math.max(0, data.remainingBytes.get()), Long::sum);

		List<QueuedDownload> aside = new ArrayList<>();
		QueuedDownload chosen = null;
		String chosenDomain = null;
		for (QueuedDownload candidate; (candidate = dispatchOrder.poll()) != null;) {
			String domain = scheduler.chooseDomain(new DownloadScheduler.QueuedFile<>(candidate.key, candidate.fileSize, candidateDomains(candidate)), inFlightBacklog);
			if (domain == null) {
				aside.add(candidate);
				continue;
			}
			chosen = candidate;
			chosenDomain = domain;
			break;
		}
		dispatchOrder.addAll(aside);
		if (chosen == null) return false;

		QueuedDownload task = chosen;
		FileInspection.HashPathPair key = chosen.key;
		String activeDomain = chosenDomain;

		boolean hostServed = activeDomain.equals(INTERNAL_CLIENT_SOURCE) && transport != null;
		if (hostServed) {
			if (!transport.hasWireRoom()) {
				// The transport's wire window is full: put the task back; the next settle re-runs the dispatch.
				WireTrace.log("WINDOW_FULL", "task", task.file.getFileName());
				requeue(key, task);
				return false;
			}
		} else if (platformTasksInFlight() >= PLATFORM_WORKERS) {
			requeue(key, task);
			return false;
		}

		// The dispatch is committed: the task leaves both queue structures, so a later requeue re-enters exactly once.
		queuedDownloads.remove(key);

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
		return true;
	}

	/** Puts a dispatched task back into both queue structures and re-pumps; the pump is a no-op while the drain loop runs. */
	private synchronized void requeue(FileInspection.HashPathPair key, QueuedDownload task) {
		queuedDownloads.put(key, task);
		dispatchOrder.add(task);
		downloadNext();
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

				cleanupAndFinalize(hashPathPair, task, storeFile, true, false);
				return;
			}
			// DOWNLOAD REQUIRED. A corrupt object is never a cache hit.
			// An empty file is its own source: there are no bytes to fetch from anyone, so materialize it locally and let the normal promotion judge it.
			if (task.fileSize == 0) {
				Path empty = preparePartial(hashPathPair, task);
				if (empty == null) {
					cleanupAndFinalize(hashPathPair, task, storeFile, false, false);
					return;
				}
				finishHostFile(hashPathPair, task, data, empty, null);
				return;
			}
			Path partial = preparePartial(hashPathPair, task);
			if (partial == null) {
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
					if (!transport.hasWireRoom()) { // window full: requeue, the next settle rediscovers this task
						WireTrace.log("WINDOW_FULL", "task", task.file.getFileName());
						downloadsInProgress.remove(hashPathPair);
						activeTemporaryFiles.remove(hashPathPair);
						requeue(hashPathPair, task);
						return;
					}
					data.hostServed = true;
				}
				downloadFromHost(hashPathPair, task, data, partial);
			} else {
				boolean success = attemptPlatformDownload(hashPathPair, task, data, source, partial);
				promotePlatformDownload(hashPathPair, task, storeFile, cache, partial, success);
			}
		} catch (InterruptedException e) {
			task.lastFailureCategory = FailureCategory.CANCELLED;
			cleanupAndFinalize(hashPathPair, task, storeFile, false, true);
		} catch (Exception e) {
			if (cancelled || Thread.currentThread().isInterrupted()) {
				task.lastFailureCategory = FailureCategory.CANCELLED;
				cleanupAndFinalize(hashPathPair, task, storeFile, false, true);
			} else {
				if (task.lastFailureCategory == null) task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
				LOGGER.warn("Unexpected error processing {}", task.file, e);
				cleanupAndFinalize(hashPathPair, task, storeFile, false, false);
			}
		}
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

	/** The blocking platform path: one HTTP download from the picked source, resuming behind the stored partial. The transport's wire window is not involved. */
	private boolean attemptPlatformDownload(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, DownloadSource source, Path partial) throws InterruptedException {
		refreshDeadLinkSources(hashPathPair.hash(), task);
		long offset = PartialResume.offset(partial, task.fileSize);
		long attemptStart = System.nanoTime();
		AtomicLong attemptBytes = new AtomicLong(0);
		// One hook for everything the written bytes mean: global progress, display speed, this attempt's sample and the in-flight backlog left for the scheduler.
		IntConsumer progressAction = bytes -> {
			updateNetworkProgress(bytes);
			attemptBytes.addAndGet(bytes);
			data.remainingBytes.addAndGet(-bytes);
		};
		try {
			httpDownloader.download(source, partial, offset, progressAction);
		} catch (LocalStorageException e) {
			task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
			LOGGER.warn("Failed to write temporary CAS object {}", hashPathPair.hash(), e);
			deletePartial(task);
			return false;
		} catch (HttpFileDownloader.HttpStatusException e) {
			task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
			if (isDeadPlatformLink(source, e)) markDeadPlatformLink(hashPathPair.hash(), task);
			else if (source.provider() == DownloadSource.Provider.CURSEFORGE && e.statusCode() == HTTP_UNAUTHORIZED) {
				LOGGER.warn("CurseForge rejected the download API key with HTTP 401; trying the next source");
				task.domainFailures.merge(data.activeDomain, MAX_DOWNLOAD_ATTEMPTS, Integer::sum);
			}
			return false;
		} catch (StaleRangeException e) {
			// The partial cannot serve as the resume prefix: worthless, so the next attempt starts clean.
			task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
			LOGGER.warn("Stored partial for CAS object {} is stale for resume; restarting from zero", hashPathPair.hash());
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

	/** The host path: the transport owns the whole transfer - resume, tiling, pacing - and its future finalizes the task, so the worker is free at once. */
	private void downloadFromHost(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, Path partial) {
		long attemptStart = System.nanoTime();
		AtomicLong attemptBytes = new AtomicLong(0);
		// One hook for everything the written bytes mean: global progress, display speed and the in-flight backlog left for the scheduler.
		IntConsumer progressAction = bytes -> {
			updateNetworkProgress(bytes);
			attemptBytes.addAndGet(bytes);
			data.remainingBytes.addAndGet(-bytes);
		};
		transport.downloadObject(hashPathPair.hash().getBytes(StandardCharsets.UTF_8), partial, task.fileSize, progressAction).whenComplete((path, error) -> {
			// Every byte that arrived is real bandwidth data for the path it actually travelled, whatever happened to the transfer.
			if (attemptBytes.get() > 0) scheduler.report(data.activeDomain, attemptBytes.get(), System.nanoTime() - attemptStart);
			try {
				downloadExecutor.execute(() -> finishHostFile(hashPathPair, task, data, partial, error));
			} catch (RejectedExecutionException rejected) {
				finishHostFile(hashPathPair, task, data, partial, error); // shutdown: end the task inline rather than zombie it
			}
		});
	}

	/** The transfer's outcome: promotion judges the assembled partial, the failure category routes the retry. */
	private void finishHostFile(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, Path partial, Throwable error) {
		if (error != null) {
			error = Throwables.unwrap(error);
			if (error instanceof WireWindowFullException) {
				// The window filled between the dispatch peek and the transfer's first take: requeue, the next settle rediscovers this task.
				downloadsInProgress.remove(hashPathPair);
				activeTemporaryFiles.remove(hashPathPair);
				requeue(hashPathPair, task);
				return;
			}
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
				if (task.partialFile != null && !Files.exists(task.partialFile)) task.partialFile = null; // the transport deleted a hole-riddled partial; the next attempt starts from a fresh temp
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
		return source != null && (e.statusCode() == HTTP_NOT_FOUND || e.statusCode() == HTTP_GONE);
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
				downloadedCount.incrementAndGet();
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
			if (downloadsInProgress.isEmpty() && queuedDownloads.isEmpty() && transport != null) LOGGER.info("[download] {} window summary: {}", task.file.getFileName(), transport.windowSummary());
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
			WireTrace.log("REQUEUE", "task", task.file.getFileName(), "attempts", task.attempts, "category", task.lastFailureCategory);
			LOGGER.warn("Retrying download: {}", task.file.getFileName());
			task.attempts++;
			requeue(key, task);
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
		return downloadedCount.get() + "/" + totalFilesAdded;
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
		synchronized (this) {
			queuedDownloads.clear();
			dispatchOrder.clear();
		}
		downloadsInProgress.forEach((k, v) -> v.future.cancel(false));
		// Only partials without a live writer are swept here: host transfers die with their aborted lanes and the
		// transport deletes a partial its positioned writers hole-riddled, and a platform attempt winds down at its
		// own request timeout - both stay in downloadsInProgress until their task ends, so the sweep never unlinks
		// a file a writer still holds.
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
		downloadedCount.set(0);
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
		/** The queue key this task lives under, set once at enqueue; the dispatch queue cannot find the task without it. */
		public FileInspection.HashPathPair key;
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
		public FileInspection.HashPathPair key;
		public QueuedDownload task;
		/** True when this task's transfer belongs to the attached host transport; platform tasks occupy a worker, host tasks do not. */
		public boolean hostServed;

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
