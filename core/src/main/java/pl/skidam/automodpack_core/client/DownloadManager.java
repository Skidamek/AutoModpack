package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.client.DownloadData.Route;
import static pl.skidam.automodpack_core.client.RescuePolicy.HOST_DOMAIN;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.platforms.FetchManager;
import pl.skidam.automodpack_core.platforms.PlatformCache;
import pl.skidam.automodpack_core.protocol.HostTiles;
import pl.skidam.automodpack_core.protocol.LocalStorageException;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.protocol.StaleRangeException;
import pl.skidam.automodpack_core.protocol.UserRejectedCertificateException;
import pl.skidam.automodpack_core.protocol.WireTrace;
import pl.skidam.automodpack_core.screen.DownloadView;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.DownloadScheduler;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.Throwables;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The pack acquisition queue: which file goes out next and on which route, the retry ladder that closes a file out, and
 * the run the screen polls. The three machines under it are theirs - {@link DownloadQueue} holds the queue and the worker
 * budget, {@link RescuePolicy} decides when the host wire takes a platform domain's traffic, and {@link PlatformAttempt}
 * with {@link ObjectPromotion} run one attempt's pull and promote what it wrote.
 */
public class DownloadManager implements DownloadView {

	public enum FailureCategory {
		/** Nothing judged this yet: an unexpected error, not a verdict, so it never refuses a retry on its own. */
		UNKNOWN,
		REMOTE_SOURCE,
		LOCAL_STORAGE,
		/** The player rejected the host's certificate: final, and never another attempt against this endpoint. */
		TRUST_REJECTED,
		CANCELLED
	}

	/** How one attempt ended, and the only thing that decides what it is charged: a reroute is the rescue's own verdict, not a failure. */
	public enum AttemptOutcome {
		COMPLETED, FAILED, REROUTED, CANCELLED
	}

	static final int MAX_DOWNLOAD_ATTEMPTS = 2;
	// Platform attempts are blocking whole-file HTTP pulls, unrelated to the host wire's lane count this used to be
	// welded to: five concurrent CDN downloads saturate any home link while bounding the parallelism any one CDN sees.
	static final int PLATFORM_WORKERS = 5;

	private final ExecutorService downloadExecutor;
	private final HttpFileDownloader httpDownloader = new HttpFileDownloader();
	private final DownloadScheduler scheduler = new DownloadScheduler();
	private final RescuePolicy rescue = new RescuePolicy(scheduler);
	private final HostProbe probe;
	private final DownloadQueue queue = new DownloadQueue();
	private final ObjectPromotion promotion;
	private final PlatformAttempt platform;
	private final DataRootResolver.Layout dataLayout;

	private volatile PackTransport transport = null;
	private volatile HostTiles hostTiles = null;
	private volatile boolean cancelled = false;

	// The acquisition summary outlives cancelAllAndShutdown, unlike downloadedCount which resets for the stage line; each
	// task records at most once per run, so plain counters mirror what the recorded results map produced.
	private final AtomicLong acquiredFiles = new AtomicLong(0);
	private final AtomicLong failedFiles = new AtomicLong(0);
	private final AtomicLong totalBytesToDownload = new AtomicLong(0);
	private final AtomicLong totalBytesDownloaded = new AtomicLong(0);
	private final AtomicInteger downloadedCount = new AtomicInteger();
	private int totalFilesAdded = 0;
	private int enqueueSequence = 0;

	// Guards the pump against re-entrancy: a request that settles inline inside a dispatch must not recurse the loop - the
	// running iteration covers the freed slot.
	private boolean pumping;
	/** One probe at a time: the flag is what keeps a burst of settle-driven passes from borrowing a file each. */
	private boolean probing;
	/** Set when the host refused to answer a probe, so the run stops asking; the failure ladder still routes to it. */
	private boolean hostUnprobeable;

	private final Semaphore semaphore = new Semaphore(0);
	private final Speedometer speedometer = new Speedometer();

	/** An in-flight platform attempt the rescue wants moved off its domain. */
	private record Rerouted(String task, String domain, HttpFileDownloader.AttemptControl control) {}

	public DownloadManager(long bytesToDownload, DataRootResolver.Layout dataLayout, PlatformCache platformCache) {
		totalBytesToDownload.set(bytesToDownload);
		speedometer.setExpectedBytes(bytesToDownload);
		this.dataLayout = Objects.requireNonNull(dataLayout, "dataLayout");
		// The dispatch discipline caps platform-routed tasks at PLATFORM_WORKERS, so the pool itself stays unbounded: a
		// host-routed task holds its thread only until the take is submitted, and a platform download parked on a slow
		// body can never starve a host dispatch of its thread.
		this.downloadExecutor = Executors.newCachedThreadPool(new CustomThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build());
		this.promotion = new ObjectPromotion(this.dataLayout);
		// Owns the batched platform-metadata refetch of this run: concurrent dead links share the bulk API calls.
		this.platform = new PlatformAttempt(httpDownloader, new FetchManager(List.of(), Objects.requireNonNull(platformCache, "platformCache")));
		this.probe = new HostProbe(scheduler, rescue);
	}

	public void attachTransport(PackTransport transport) {
		this.transport = transport;
		this.hostTiles = transport instanceof HostTiles tiles ? tiles : null;
	}

	public synchronized void download(Path file, String sha1, String murmur, String fileType, List<DownloadSource> sources, long fileSize, Runnable successCallback, Runnable failureCallback) {
		download(file, sha1, murmur, fileType, sources, fileSize, successCallback, ignored -> failureCallback.run());
	}

	public synchronized void download(Path file, String sha1, String murmur, String fileType, List<DownloadSource> sources, long fileSize, Runnable successCallback,
			Consumer<FailureCategory> failureCallback) {
		FileInspection.HashPathPair hashPathPair = new FileInspection.HashPathPair(sha1, file);
		if (queue.queued(hashPathPair)) return;
		queue.enqueue(new QueuedDownload(hashPathPair, file, sources, murmur, fileType, fileSize, enqueueSequence++, successCallback, failureCallback));
		totalFilesAdded++;
		downloadNext();
	}

	/**
	 * One pass of the run: the rescue re-reads its verdict first - a cancelled attempt notices it on its own blocked read -
	 * then the queue drains until nothing queued can run.
	 */
	private void downloadNext() {
		List<Rerouted> reroutes;
		synchronized (this) {
			reroutes = rescueReroutes();
		}
		// Cancelling closes an HTTP response stream inside the JDK's connection pool, so it never runs under the monitor.
		for (Rerouted rerouted : reroutes) {
			WireTrace.log("RESCUE_CANCEL", "task", rerouted.task(), "domain", rerouted.domain());
			rerouted.control().cancel();
		}
		drain();
	}

	/**
	 * The host's first fact, when it has none and the run is on a platform nobody has measured past the bar. It takes one
	 * tile from the far end of an object the platform is already sending, so there is always an object to take from and
	 * neither side sends bytes the other is sending. The take is never cancelled and never charged.
	 */
	private void probeHost() {
		if (hostTiles == null) return;
		DownloadData data;
		Path directory;
		long tileStart;
		synchronized (this) {
			if (hostUnprobeable || probing) return;
			DownloadData chosen = null;
			Path chosenDirectory = null;
			long chosenTile = -1;
			for (DownloadData inFlight : HostProbe.platformAttempts(queue.inFlightTasks())) {
				if (!probe.worthMeasuring(inFlight)) continue;
				Path slices = PartialResume.directory(dataLayout.stagingDirectory(), inFlight.sha1);
				long tile;
				try {
					tile = HostProbe.takeableTile(slices, inFlight.fileSize);
				} catch (IOException e) {
					continue;
				}
				if (tile < 0) continue;
				chosen = inFlight;
				chosenDirectory = slices;
				chosenTile = tile;
				break;
			}
			if (chosen == null) return;
			probing = true;
			data = chosen;
			directory = chosenDirectory;
			tileStart = chosenTile;
		}
		LOGGER.debug("[download] measuring the host wire from tile {} of {}", tileStart, data.getFileName());
		probe.probe(hostTiles, directory, data, tileStart, outcome -> {
			synchronized (DownloadManager.this) {
				probing = false;
				if (outcome == HostProbe.Outcome.UNREACHABLE) hostUnprobeable = true;
			}
			if (outcome == HostProbe.Outcome.MEASURED) LOGGER.info("[download] The host wire measured tile {} at {}", tileStart, scheduler.measuredSpeed(RescuePolicy.HOST_DOMAIN));
			drain();
		});
	}

	/** Every in-flight platform attempt whose domain the host now outruns by the engage ratio, collected under the monitor and cancelled outside it. */
	private List<Rerouted> rescueReroutes() {
		List<Rerouted> reroutes = null;
		for (DownloadData data : queue.inFlightTasks()) {
			if (data.route != Route.PLATFORM || data.attemptControl == null || !rescue.hostFarFaster(data.activeDomain)) continue;
			if (reroutes == null) reroutes = new ArrayList<>();
			reroutes.add(new Rerouted(data.getFileName(), data.activeDomain, data.attemptControl));
			rescue.engaged(data.activeDomain);
		}
		return reroutes == null ? List.of() : reroutes;
	}

	/**
	 * Drain, don't dribble: every settle, every finalize and every speed sample re-enters here, so it runs until nothing
	 * dispatchable is left. Host transfers occupy no worker, so they all dispatch at once and their takes queue on the
	 * transport's lanes.
	 */
	private void drain() {
		synchronized (this) {
			if (pumping) return;
			pumping = true;
			try {
				while (queue.queuedCount() > 0 && dispatchOne()) {
				}
			} finally {
				pumping = false;
			}
		}
		probeHost();
	}

	/**
	 * One dispatch: the largest queued task that can run now, sent to the source its measured speed picked among the ones
	 * on offer. The backlog is a snapshot over every in-flight task, so it is built only when more than one domain is
	 * actually on offer - a host-only wave never pays for it.
	 */
	private boolean dispatchOne() {
		QueuedDownload task = queue.pollDispatchable(this::dispatchable);
		if (task == null) return false;
		List<String> candidates = candidateDomains(task);
		String domain = scheduler.chooseDomain(new DownloadScheduler.QueuedFile<>(task.key, task.fileSize, candidates), candidates.size() > 1 ? queue.inFlightBacklog() : Map.of());
		LOGGER.debug("Queueing download for: {} {} {}", task.file, task.fileSize, domain);
		DownloadData data = new DownloadData(task.file, task.key.hash(), domain, task.fileSize, task.domains);
		data.route = domain.equals(HOST_DOMAIN) ? Route.HOST : Route.PLATFORM;
		queue.start(task.key, data);
		if (cancelled || downloadExecutor.isShutdown()) {
			queue.finish(task.key);
			failedFiles.incrementAndGet();
			semaphore.release();
			return true;
		}
		try {
			downloadExecutor.execute(() -> {
				try {
					processDownloadTask(task.key, task, data);
				} catch (Throwable error) {
					LOGGER.error("Fatal error executing download task for {}", task.file.getFileName(), error);
				}
			});
		} catch (RuntimeException error) {
			queue.finish(task.key);
			failedFiles.incrementAndGet();
			semaphore.release();
			// A non-rejected execution is a programming error and still fails loudly after the cleanup.
			if (!(error instanceof RejectedExecutionException)) throw error;
		}
		return true;
	}

	/** Which routes a task can run on right now: the host wire when it is one of its domains, a platform source only on a free worker. */
	private boolean dispatchable(QueuedDownload task) {
		List<String> domains = candidateDomains(task);
		if (transport != null && domains.contains(HOST_DOMAIN)) return true;
		return queue.platformTasks() < PLATFORM_WORKERS && domains.stream().anyMatch(domain -> !domain.equals(HOST_DOMAIN));
	}

	/**
	 * The domains this task may use, in preference order: its platform sources whose attempt budget is left, plus the
	 * attached host wire ahead of them while the rescue engages. Always at least one, so a dispatched task always has a route
	 * to name - a task whose only route is the host wire and has none simply has no dispatchable route and waits. Domains
	 * this task already burned attempts on are withheld, so a retry dispatches elsewhere instead of re-picking the same one;
	 * a task whose whole platform budget is burned falls back to the host wire.
	 */
	private List<String> candidateDomains(QueuedDownload task) {
		if (task.sources.isEmpty()) return List.of(HOST_DOMAIN);
		List<String> domains = task.domains.stream().filter(domain -> task.domainFailures.getOrDefault(domain, 0) < MAX_DOWNLOAD_ATTEMPTS).toList();
		if (domains.isEmpty()) return transport != null ? List.of(HOST_DOMAIN) : task.domains;
		if (transport == null || task.domainFailures.getOrDefault(HOST_DOMAIN, 0) >= MAX_DOWNLOAD_ATTEMPTS) return domains;
		return rescue.offer(domains);
	}

	private void processDownloadTask(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data) {
		Path storeFile = dataLayout.objectFile(hashPathPair.hash());
		try (FileCache cache = FileCache.open(dataLayout.fileCacheDirectory())) {
			if (ClientObjectStore.acquireVerified(storeFile, hashPathPair.hash(), task.fileSize, List.of(), cache, ClientObjectStore.CorruptObjectPolicy.EVICT_QUIETLY).present()) {
				// The run's total is remaining wire bytes, so a CAS hit only fills the amount this task still owed.
				totalBytesDownloaded.addAndGet(PartialResume.remainingBytes(dataLayout.stagingDirectory(), hashPathPair.hash(), task.fileSize));
				cleanupAndFinalize(hashPathPair, task, storeFile, AttemptOutcome.COMPLETED);
				return;
			}
			// DOWNLOAD REQUIRED. A corrupt object is never a cache hit.
			// An empty file is its own source: there are no bytes to fetch from anyone, so materialize it locally and let the
			// normal promotion judge it.
			if (task.fileSize == 0) {
				Path empty = promotion.preparePartial(hashPathPair.hash(), task);
				if (empty == null) {
					cleanupAndFinalize(hashPathPair, task, storeFile, AttemptOutcome.FAILED);
					return;
				}
				finishHostFile(hashPathPair, task, empty, null);
				return;
			}
			Path partial = promotion.preparePartial(hashPathPair.hash(), task);
			if (partial == null) {
				cleanupAndFinalize(hashPathPair, task, storeFile, AttemptOutcome.FAILED);
				return;
			}
			long already = PartialResume.presentBytes(partial, task.fileSize);
			if (already > 0) data.remainingBytes.addAndGet(-already);
			platform.refreshDeadLinkSources(hashPathPair.hash(), task);
			TransferSampler sampler = new TransferSampler(scheduler, data, this::updateNetworkProgress, transport != null ? NetUtils.TAKE_RATE_FLOOR_BYTES_PER_SECOND : 0, this::downloadNext);
			DownloadSource source = data.route == Route.HOST ? null : platform.sourceFor(task, data.activeDomain);
			// A task parked in the metadata refetch held no cancel handle while the estimates moved on, so its route is
			// decided again here from the state the rescue reads, not from the verdict dispatch saw.
			if (source != null && rescue.hostFarFaster(data.activeDomain)) source = null;
			if (source != null) {
				AttemptOutcome outcome = platform.run(hashPathPair, task, data, source, partial, sampler);
				if (outcome == AttemptOutcome.COMPLETED) {
					try (FileCache openCache = FileCache.open(dataLayout.fileCacheDirectory())) {
						outcome = promotion.promote(hashPathPair.hash(), task, partial, openCache) ? AttemptOutcome.COMPLETED : AttemptOutcome.FAILED;
					} catch (IOException e) {
						task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
						outcome = AttemptOutcome.FAILED;
					}
				}
				cleanupAndFinalize(hashPathPair, task, storeFile, outcome);
				return;
			}
			if (transport == null) {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				cleanupAndFinalize(hashPathPair, task, storeFile, AttemptOutcome.FAILED);
				return;
			}
			// The flip is the accounting: the task stops occupying a platform worker the moment its bytes move to the host wire.
			data.route = Route.HOST;
			downloadFromHost(hashPathPair, task, data, partial, sampler);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			cleanupAndFinalize(hashPathPair, task, storeFile, AttemptOutcome.CANCELLED);
		} catch (Exception e) {
			if (cancelled || Thread.currentThread().isInterrupted()) {
				cleanupAndFinalize(hashPathPair, task, storeFile, AttemptOutcome.CANCELLED);
			} else {
				task.lastFailureCategory = FailureCategory.UNKNOWN;
				LOGGER.warn("Unexpected error processing {}", task.file, e);
				cleanupAndFinalize(hashPathPair, task, storeFile, AttemptOutcome.FAILED);
			}
		}
	}

	/** The host path: the transport owns the whole transfer - resume, tiling, flow control - and its future finalizes the task, so the worker is free at once. */
	private void downloadFromHost(FileInspection.HashPathPair hashPathPair, QueuedDownload task, DownloadData data, Path partial, TransferSampler sampler) {
		transport.downloadObject(hashPathPair.hash().getBytes(StandardCharsets.UTF_8), partial, task.fileSize, sampler.progressHook()).whenComplete((path, error) -> {
			// Every byte that arrived is real bandwidth data for the path it actually travelled, whatever happened to the transfer.
			sampler.flush();
			try {
				downloadExecutor.execute(() -> finishHostFile(hashPathPair, task, partial, error));
			} catch (RejectedExecutionException rejected) {
				finishHostFile(hashPathPair, task, partial, error); // shutdown: end the task inline rather than zombie it
			}
		});
	}

	/** The transfer's outcome: promotion judges the assembled partial, the failure category routes the retry. */
	private void finishHostFile(FileInspection.HashPathPair hashPathPair, QueuedDownload task, Path partial, Throwable error) {
		Path storeFile = dataLayout.objectFile(hashPathPair.hash());
		if (error != null) {
			Throwable failure = Throwables.unwrap(error);
			if (cancelled || failure instanceof InterruptedException) {
				cleanupAndFinalize(hashPathPair, task, storeFile, AttemptOutcome.CANCELLED);
				return;
			}
			if (failure instanceof UserRejectedCertificateException) {
				// The player's no is final for this endpoint: charging it as a remote failure would spend the retry budget
				// on a host that will never open a lane again.
				task.lastFailureCategory = FailureCategory.TRUST_REJECTED;
			} else if (failure instanceof StaleRangeException) {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				ObjectPromotion.deletePartial(task);
			} else if (failure instanceof LocalStorageException) {
				task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
				ObjectPromotion.deletePartial(task);
			} else {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				LOGGER.debug("Remote source failed for CAS object {}", hashPathPair.hash(), failure);
			}
			cleanupAndFinalize(hashPathPair, task, storeFile, AttemptOutcome.FAILED);
			return;
		}
		try (FileCache cache = FileCache.open(dataLayout.fileCacheDirectory())) {
			cleanupAndFinalize(hashPathPair, task, storeFile, promotion.promote(hashPathPair.hash(), task, partial, cache) ? AttemptOutcome.COMPLETED : AttemptOutcome.FAILED);
		} catch (IOException e) {
			task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
			cleanupAndFinalize(hashPathPair, task, storeFile, AttemptOutcome.FAILED);
		}
	}

	private void cleanupAndFinalize(FileInspection.HashPathPair key, QueuedDownload task, Path storeFile, AttemptOutcome outcome) {
		DownloadData data = queue.finish(key);
		// A failed attempt counts against the domain that served it, so the retry dispatches elsewhere before the attempts
		// budget forces the task to give up. A reroute is the rescue's own verdict and a cancellation the player's, so
		// neither charges the domain.
		if (data != null && outcome == AttemptOutcome.FAILED) task.domainFailures.merge(data.activeDomain, 1, Integer::sum);
		try {
			if (outcome == AttemptOutcome.COMPLETED) {
				downloadedCount.incrementAndGet();
				acquiredFiles.incrementAndGet();
				LOGGER.debug("Acquired CAS object {} for {}", storeFile.getFileName(), task.file.getFileName());
				try {
					task.successCallback.run();
				} finally {
					semaphore.release();
				}
			} else {
				handleRetry(key, task, outcome);
			}
		} finally {
			if (queue.isIdle() && transport != null) LOGGER.info("[download] {} transfer summary: {}", task.file.getFileName(), transport.windowSummary());
			if (outcome != AttemptOutcome.CANCELLED && !cancelled && !downloadExecutor.isShutdown()) downloadNext();
		}
	}

	/** Requeues the task for another attempt, or ends it for good: a cancelled run, a local failure and a rejected certificate never retry. */
	private void handleRetry(FileInspection.HashPathPair key, QueuedDownload task, AttemptOutcome outcome) {
		if (outcome == AttemptOutcome.CANCELLED || cancelled) {
			failedFiles.incrementAndGet();
			semaphore.release();
			return;
		}
		if (task.lastFailureCategory != FailureCategory.LOCAL_STORAGE && task.lastFailureCategory != FailureCategory.TRUST_REJECTED
				&& task.attempts < (task.sources.size() + 1) * MAX_DOWNLOAD_ATTEMPTS) {
			WireTrace.log("REQUEUE", "task", task.file.getFileName(), "attempts", task.attempts, "category", task.lastFailureCategory);
			LOGGER.debug("Retrying download: {}", task.file.getFileName());
			task.attempts++;
			requeue(key, task);
			return;
		}
		WireTrace.log("FAIL", "task", task.file.getFileName(), "attempts", task.attempts, "category", task.lastFailureCategory);
		failedFiles.incrementAndGet();
		LOGGER.error("Permanently failed to download {} ({})", task.file.getFileName(), task.lastFailureCategory);
		ObjectPromotion.deletePartial(task);
		try {
			task.failureCallback.accept(task.lastFailureCategory);
		} finally {
			semaphore.release();
		}
	}

	/** Puts a dispatched task back into the queue and re-pumps; the pump is a no-op while the drain loop runs. */
	private synchronized void requeue(FileInspection.HashPathPair key, QueuedDownload task) {
		queue.enqueue(task);
		downloadNext();
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
		synchronized (this) {
			LOGGER.info("Cancelling the download run: {} queued, {} in-flight", queue.queuedCount(), queue.inFlightCount());
			queue.clearQueued();
		}
		// Slice directories outlive the run on purpose, so the next click resumes; a task still winding down removes itself
		// from the in-flight ledger when it ends, and a platform attempt winds down at its own request timeout.
		semaphore.release(totalFilesAdded);
		downloadedCount.set(0);
		downloadExecutor.shutdown();
	}

	/** Snapshot of finished acquisitions, what the acquisition summary line renders. */
	@Override
	public long acquired() {
		return acquiredFiles.get();
	}

	@Override
	public long failed() {
		return failedFiles.get();
	}

	@Override
	public List<String> downloadingFileNames() {
		return queue.downloadingFileNames();
	}

	public boolean isCancelled() {
		return cancelled;
	}
}
