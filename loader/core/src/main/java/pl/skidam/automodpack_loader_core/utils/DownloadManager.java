package pl.skidam.automodpack_loader_core.utils;

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

import pl.skidam.automodpack_core.platforms.CurseForgeAPI;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.LocalStorageException;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.PlatformMetadataCache;

public class DownloadManager {

	public enum FailureCategory {
		REMOTE_SOURCE,
		LOCAL_STORAGE,
		CANCELLED
	}

	public record AcquisitionResult(boolean success, FailureCategory failureCategory) {}

	private record DeadLink(String murmur, String fileType) {}

	private static final int MAX_DOWNLOADS_IN_PROGRESS = 5;
	private static final int MAX_DOWNLOAD_ATTEMPTS = 2;

	private final ExecutorService downloadExecutor;

	private final HttpFileDownloader httpDownloader = new HttpFileDownloader();
	private DownloadClient downloadClient = null;
	private final PlatformMetadataCache platformMetadataCache;

	private volatile boolean cancelled = false;

	// --- QUEUES ---
	private final Map<FileInspection.HashPathPair, QueuedDownload> queuedDownloads = new ConcurrentHashMap<>();
	public final Map<FileInspection.HashPathPair, DownloadData> downloadsInProgress = new ConcurrentHashMap<>();
	private final Map<FileInspection.HashPathPair, Path> activeTemporaryFiles = new ConcurrentHashMap<>();
	private final Map<FileInspection.HashPathPair, AcquisitionResult> acquisitionResults = new ConcurrentHashMap<>();

	private final Map<String, Integer> activeDownloadsPerSource = new ConcurrentHashMap<>();

	// --- DEAD LINK INVALIDATION ---
	private final Object metadataRefetchLock = new Object();
	private final Map<String, DeadLink> pendingMetadataRefetch = new HashMap<>();
	private final Map<String, List<DownloadSource>> resolvedMetadataRefetches = new HashMap<>();
	private final Set<String> refetchedSha1s = new HashSet<>();

	// --- PROGRESS TRACKING ---
	private final AtomicLong totalBytesToDownload = new AtomicLong(0);
	private final AtomicLong totalBytesDownloaded = new AtomicLong(0);
	private int totalFilesAdded = 0;
	private int downloadedCount = 0;

	private final Semaphore semaphore = new Semaphore(0);
	private final Speedometer speedometer = new Speedometer();
	private final ClientStorage storage;

	public DownloadManager(long bytesToDownload, ClientStorage storage, PlatformMetadataCache platformMetadataCache) {
		this.totalBytesToDownload.set(bytesToDownload);
		this.speedometer.setExpectedBytes(bytesToDownload);
		this.storage = Objects.requireNonNull(storage, "storage");
		this.downloadExecutor = Executors.newFixedThreadPool(MAX_DOWNLOADS_IN_PROGRESS,
				new CustomThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build());
		this.platformMetadataCache = Objects.requireNonNull(platformMetadataCache, "platformMetadataCache");
	}

	public void attachDownloadClient(DownloadClient downloadClient) {
		this.downloadClient = downloadClient;
	}

	public synchronized void download(Path file, String sha1, String murmur, String fileType, List<DownloadSource> sources, long fileSize, Runnable successCallback, Runnable failureCallback) {
		download(file, sha1, murmur, fileType, sources, fileSize, successCallback, ignored -> failureCallback.run());
	}

	public synchronized void download(Path file, String sha1, String murmur, String fileType, List<DownloadSource> sources, long fileSize, Runnable successCallback,
			Consumer<FailureCategory> failureCallback) {
		FileInspection.HashPathPair hashPathPair = new FileInspection.HashPathPair(sha1, file);
		if (queuedDownloads.containsKey(hashPathPair)) return;

		QueuedDownload task = new QueuedDownload(file, new ArrayList<>(sources), murmur, fileType, fileSize, 0, successCallback, failureCallback);
		queuedDownloads.put(hashPathPair, task);
		totalFilesAdded++;
		downloadNext();
	}

	private synchronized void downloadNext() {
		if (downloadsInProgress.size() >= MAX_DOWNLOADS_IN_PROGRESS || queuedDownloads.isEmpty()) return;

		// --- 1. CALCULATE METRICS ---

		long totalBytes = totalBytesToDownload.get();
		if (totalBytes <= 0) totalBytes = 1;
		if (totalFilesAdded <= 0) totalFilesAdded = 1;

		// Dynamic Average (Pivot for Big vs Small)
		long avgSize = totalBytes / totalFilesAdded;

		// Calculate Progress Percentages (0.00 to 1.00)
		double byteProgress = (double) totalBytesDownloaded.get() / totalBytes;
		double fileProgress = (double) downloadedCount / totalFilesAdded;

		// Calculate LAG
		// Example: 50% Bytes Done, 40% Files Done -> Lag = 0.10 (BAD)
		double lag = byteProgress - fileProgress;

		// --- 2. DETERMINE SHARES (Proportional Control) ---
		// We decide what % of our threads should be working on Big Files.
		double targetBigShare;

		if (lag > 0.02) targetBigShare = 0.0; // Panic (>2% Behind): 0% Big, 100% Small
		else if (lag > 0.005) targetBigShare = 0.2; // Warning (>0.5% Behind): 20% Big (1/5)
		else if (lag < -0.15) targetBigShare = 1.0; // Ahead (>15%): 100% Big
		else if (lag < -0.10) targetBigShare = 0.8; // Ahead (>10%): 80% Big (4/5)
		else if (lag < -0.05) targetBigShare = 0.6; // Ahead (>5%): 60% Big (3/5)
		else targetBigShare = 0.4; // Balanced: 40% Big (2/5)

		int slotsForBig = (int) Math.round(MAX_DOWNLOADS_IN_PROGRESS * targetBigShare);
		int slotsForSmall = MAX_DOWNLOADS_IN_PROGRESS - slotsForBig;

		// --- 3. COUNT CURRENT STATE ---

		int activeBig = 0;
		int activeSmall = 0;
		for (DownloadData d : downloadsInProgress.values()) {
			if (d.fileSize > avgSize) activeBig++;
			else activeSmall++;
		}

		// --- 4. DECISION ---

		boolean preferBig = activeBig < slotsForBig || activeSmall > slotsForSmall;

		// --- 5. AVAILABILITY CHECK ---

		boolean hasBig = false;
		boolean hasSmall = false;

		// Fast scan
		for (QueuedDownload t : queuedDownloads.values()) {
			if (t.fileSize > avgSize) hasBig = true;
			else hasSmall = true;
			if (hasBig && hasSmall) break; // Found both
		}

		// Fallback Logic
		if (preferBig && !hasBig) preferBig = false; // Wanted Big, but none left. Take Small.
		if (!preferBig && !hasSmall) preferBig = true; // Wanted Small, but none left. Take Big.

		// --- 6. SELECT BEST FILE ---

		FileInspection.HashPathPair bestKey = null;
		QueuedDownload bestTask = null;
		String bestSource = null;
		int lowestLoad = Integer.MAX_VALUE;

		for (Map.Entry<FileInspection.HashPathPair, QueuedDownload> entry : queuedDownloads.entrySet()) {
			QueuedDownload task = entry.getValue();
			boolean isBig = task.fileSize > avgSize;

			// FILTER: Strict Type Check
			if (isBig != preferBig) continue;

			String source = predictSource(task);
			int activeInSource = activeDownloadsPerSource.getOrDefault(source, 0);

			// Source Cap (Optional: set to 2 or 3 per source if needed)
			if (activeInSource >= MAX_DOWNLOADS_IN_PROGRESS) continue;

			// Load Balancing: Pick least busy source
			if (activeInSource < lowestLoad) {
				lowestLoad = activeInSource;
				bestKey = entry.getKey();
				bestTask = task;
				bestSource = source;
			}
		}

		// FINAL FALLBACK:
		// If strict filtering failed (e.g. we wanted Small but all Small domains are capped),
		// we MUST pick something else to avoid idling threads.
		if (bestTask == null) {
			// Try to find *any* valid download regardless of size
			for (Map.Entry<FileInspection.HashPathPair, QueuedDownload> entry : queuedDownloads.entrySet()) {
				QueuedDownload task = entry.getValue();
				String source = predictSource(task);
				if (activeDownloadsPerSource.getOrDefault(source, 0) < MAX_DOWNLOADS_IN_PROGRESS) {
					bestKey = entry.getKey();
					bestTask = task;
					bestSource = source;
					break;
				}
			}
		}

		if (bestTask == null) return;

		// --- EXECUTE ---
		queuedDownloads.remove(bestKey);
		activeDownloadsPerSource.merge(bestSource, 1, Integer::sum);

		final FileInspection.HashPathPair key = bestKey;
		final QueuedDownload task = bestTask;
		final String activeDomain = bestSource;

		LOGGER.info("Queuning download for: {} {} {}", task.file, task.fileSize, activeDomain);

		CompletableFuture<Void> future = new CompletableFuture<>();
		downloadsInProgress.put(key, new DownloadData(future, task.file, activeDomain, task.fileSize));
		try {
			downloadExecutor.execute(() -> {
				try {
					processDownloadTask(key, task);
					future.complete(null);
				} catch (Throwable error) {
					LOGGER.error("Fatal error executing download task for {}", task.file.getFileName(), error);
					future.completeExceptionally(error);
				}
			});
		} catch (RuntimeException error) {
			downloadsInProgress.remove(key);
			activeDownloadsPerSource.compute(activeDomain, (source, count) -> (count == null || count <= 1) ? null : count - 1);
			future.completeExceptionally(error);
			throw error;
		}
	}

	private String predictSource(QueuedDownload task) {
		int numberOfIndexes = task.sources.size();
		int sourceIndex = Math.min(task.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);
		if (task.sources.size() > sourceIndex) return getDomainFromUrl(task.sources.get(sourceIndex).url());
		return "internal_client";
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

	private void processDownloadTask(FileInspection.HashPathPair hashPathPair, QueuedDownload task) {
		Path storeFile = storage.objectFile(hashPathPair.hash());
		boolean success = false;
		boolean interrupted = false;

		try {
			if (FileIntegrity.matches(storeFile, task.fileSize, hashPathPair.hash())) {
				// CACHE HIT
				totalBytesDownloaded.addAndGet(task.fileSize);
				// IMPORTANT: Do NOT add cached bytes to Speedometer.
				// It would fake a massive speed spike.

				success = true;
			} else {
				// DOWNLOAD REQUIRED. A corrupt object is never a cache hit.
				ImmutableFiles.deleteIfExists(storeFile);
				success = attemptDownload(hashPathPair, task, storeFile);
			}
		} catch (InterruptedException e) {
			interrupted = true;
			task.lastFailureCategory = FailureCategory.CANCELLED;
		} catch (Exception e) {
			if (task.lastFailureCategory == null) task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
			LOGGER.warn("Unexpected error processing {}", task.file, e);
		} finally {
			cleanupAndFinalize(hashPathPair, task, storeFile, success, interrupted);
		}
	}

	private boolean attemptDownload(FileInspection.HashPathPair hashPathPair, QueuedDownload task, Path storeFile) throws InterruptedException {
		refreshDeadLinkSources(hashPathPair.hash(), task);
		int numberOfIndexes = task.sources.size();
		int sourceIndex = Math.min(task.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);
		DownloadSource source = (task.sources.size() > sourceIndex) ? task.sources.get(sourceIndex) : null;
		Path tempStoreFile = null;

		try {
			try {
				tempStoreFile = Files.createTempFile(storage.incomingDirectory(), "." + hashPathPair.hash() + ".", ".tmp");
				activeTemporaryFiles.put(hashPathPair, tempStoreFile);
			} catch (IOException e) {
				task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
				LOGGER.warn("Failed to create temporary CAS object {}", hashPathPair.hash(), e);
				return false;
			}

			try {
				if (source != null && task.attempts < MAX_DOWNLOAD_ATTEMPTS * numberOfIndexes) {
					httpDownloader.download(source, tempStoreFile, this::updateNetworkProgress);
				} else if (downloadClient != null) {
					hostDownloadFile(hashPathPair, tempStoreFile, this::updateNetworkProgress);
				} else {
					task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
					return false;
				}
			} catch (LocalStorageException e) {
				task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
				LOGGER.warn("Failed to write temporary CAS object {}", hashPathPair.hash(), e);
				return false;
			} catch (HttpFileDownloader.HttpStatusException e) {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				if (isDeadPlatformLink(source, e)) markDeadPlatformLink(hashPathPair.hash(), task);
				else if (source != null && source.provider() == DownloadSource.Provider.CURSEFORGE && e.statusCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
					LOGGER.warn("CurseForge rejected the download API key with HTTP 401; trying the next source");
					task.attempts = (sourceIndex + 1) * MAX_DOWNLOAD_ATTEMPTS - 1;
				}
				return false;
			} catch (IOException e) {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				LOGGER.warn("Remote source failed for CAS object {}", hashPathPair.hash(), e);
				return false;
			}

			if (!FileIntegrity.matches(tempStoreFile, task.fileSize, hashPathPair.hash())) {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				LOGGER.warn("Size or hash mismatch for downloaded file {}", task.file.getFileName());
				return false;
			}
			try {
				VerifiedFileTransfer.promoteAtomic(tempStoreFile, storeFile, task.fileSize, hashPathPair.hash());
			} catch (IOException e) {
				task.lastFailureCategory = FailureCategory.LOCAL_STORAGE;
				LOGGER.warn("Failed to persist verified CAS object {}", hashPathPair.hash(), e);
				return false;
			}
			tempStoreFile = null;
			task.lastFailureCategory = null;
			return true;
		} finally {
			activeTemporaryFiles.remove(hashPathPair);
			if (tempStoreFile != null) {
				try {
					Files.deleteIfExists(tempStoreFile);
				} catch (IOException ignored) {
				}
			}
		}
	}

	private static boolean isDeadPlatformLink(DownloadSource source, HttpFileDownloader.HttpStatusException e) {
		return source != null && (e.statusCode() == HttpURLConnection.HTTP_NOT_FOUND || e.statusCode() == HttpURLConnection.HTTP_GONE);
	}

	private void markDeadPlatformLink(String sha1, QueuedDownload task) {
		String normalizedSha1 = sha1.toLowerCase(Locale.ROOT);
		synchronized (metadataRefetchLock) {
			platformMetadataCache.evict(normalizedSha1);
			if (!refetchedSha1s.add(normalizedSha1)) return;
			pendingMetadataRefetch.put(normalizedSha1, new DeadLink(task.murmur, task.fileType));
			task.needsMetadataRefetch = true;
		}
		LOGGER.warn("Dead platform link for CAS object {}; its metadata will be refetched before the next attempt", sha1);
	}

	private void refreshDeadLinkSources(String sha1, QueuedDownload task) {
		if (!task.needsMetadataRefetch) return;
		List<DownloadSource> fresh = awaitMetadataRefetch(sha1.toLowerCase(Locale.ROOT));
		task.needsMetadataRefetch = false;
		if (fresh.isEmpty()) return;
		task.sources.clear();
		task.sources.addAll(fresh);
		task.attempts = 0;
	}

	/** Waits for one batched refetch covering every sha1 whose platform link died, so concurrent dead links share the bulk calls. */
	private List<DownloadSource> awaitMetadataRefetch(String normalizedSha1) {
		synchronized (metadataRefetchLock) {
			List<DownloadSource> resolved = resolvedMetadataRefetches.remove(normalizedSha1);
			if (resolved != null) return resolved;
			if (!pendingMetadataRefetch.containsKey(normalizedSha1)) return List.of();
			Map<String, DeadLink> batch = new LinkedHashMap<>(pendingMetadataRefetch);
			pendingMetadataRefetch.clear();
			resolvedMetadataRefetches.putAll(refetchPlatformMetadata(batch));
			List<DownloadSource> fresh = resolvedMetadataRefetches.remove(normalizedSha1);
			return fresh == null ? List.of() : fresh;
		}
	}

	private Map<String, List<DownloadSource>> refetchPlatformMetadata(Map<String, DeadLink> batch) {
		Map<String, List<DownloadSource>> fresh = new HashMap<>();
		List<ModrinthAPI> modrinthInfos = ModrinthAPI.getModsInfosFromListOfSHA1(new ArrayList<>(batch.keySet()));
		if (modrinthInfos != null) for (ModrinthAPI info : modrinthInfos) {
			String sha1 = info.SHA1Hash().toLowerCase(Locale.ROOT);
			DeadLink deadLink = batch.get(sha1);
			String mainPageUrl = deadLink == null ? null : ModrinthAPI.getMainPageUrl(info.modrinthID(), deadLink.fileType());
			platformMetadataCache.putModrinth(info.SHA1Hash(), info, mainPageUrl);
			fresh.computeIfAbsent(sha1, key -> new ArrayList<>()).add(new DownloadSource(info.downloadUrl(), DownloadSource.Provider.MODRINTH));
		}
		Map<String, String> murmurs = new HashMap<>();
		for (Map.Entry<String, DeadLink> entry : batch.entrySet()) if (entry.getValue().murmur() != null && !entry.getValue().murmur().isBlank()) murmurs.put(entry.getKey(), entry.getValue().murmur());
		if (!murmurs.isEmpty()) {
			List<CurseForgeAPI> curseForgeInfos = CurseForgeAPI.getModInfosFromFingerPrints(murmurs);
			if (curseForgeInfos != null) for (CurseForgeAPI info : curseForgeInfos) {
				String sha1 = info.sha1Hash().toLowerCase(Locale.ROOT);
				platformMetadataCache.putCurseForge(info.sha1Hash(), info);
				fresh.computeIfAbsent(sha1, key -> new ArrayList<>()).add(new DownloadSource(info.downloadUrl(), DownloadSource.Provider.CURSEFORGE));
			}
		}
		return fresh;
	}

	private void cleanupAndFinalize(FileInspection.HashPathPair key, QueuedDownload task, Path storeFile, boolean success, boolean interrupted) {
		DownloadData data = downloadsInProgress.remove(key);

		if (data != null && data.activeDomain != null) {
			synchronized (this) {
				activeDownloadsPerSource.compute(data.activeDomain, (k, v) -> (v == null || v <= 1) ? null : v - 1);
			}
		}

		try {
			if (success) {
				downloadedCount++;
				acquisitionResults.put(key, new AcquisitionResult(true, null));
				LOGGER.info("Acquired CAS object {} for {}", storeFile.getFileName(), task.file.getFileName());
				try {
					task.successCallback.run();
				} finally {
					semaphore.release();
				}
			} else {
				handleRetry(key, task, interrupted);
			}
		} finally {
			if (!interrupted) downloadNext();
		}
	}

	private void handleRetry(FileInspection.HashPathPair key, QueuedDownload task, boolean interrupted) {
		if (interrupted) {
			acquisitionResults.put(key, new AcquisitionResult(false, FailureCategory.CANCELLED));
			return;
		}
		if (task.lastFailureCategory != FailureCategory.LOCAL_STORAGE && task.attempts < (task.sources.size() + 1) * MAX_DOWNLOAD_ATTEMPTS) {
			LOGGER.warn("Retrying download: {}", task.file.getFileName());
			task.attempts++;
			queuedDownloads.put(key, task);
		} else {
			FailureCategory category = task.lastFailureCategory == null ? FailureCategory.REMOTE_SOURCE : task.lastFailureCategory;
			acquisitionResults.put(key, new AcquisitionResult(false, category));
			LOGGER.error("Permanently failed to download {} ({})", task.file.getFileName(), category);
			try {
				task.failureCallback.accept(category);
			} finally {
				semaphore.release();
			}
		}
	}

	private void hostDownloadFile(FileInspection.HashPathPair hashPathPair, Path targetFile, IntConsumer progressAction)
			throws IOException, InterruptedException {
		var future = downloadClient.downloadFile(hashPathPair.hash().getBytes(StandardCharsets.UTF_8), targetFile, progressAction);
		try {
			future.join();
		} catch (CancellationException e) {
			throw new InterruptedException("AutoModpack host download was cancelled");
		} catch (CompletionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof LocalStorageException localStorageException) throw localStorageException;
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

	public void cancelAllAndShutdown() {
		cancelled = true;
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

	public Map<FileInspection.HashPathPair, AcquisitionResult> getAcquisitionResults() {
		return Map.copyOf(acquisitionResults);
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
		public int attempts;
		public final Runnable successCallback;
		public final Consumer<FailureCategory> failureCallback;
		public FailureCategory lastFailureCategory;
		public boolean needsMetadataRefetch;

		public QueuedDownload(Path f, List<DownloadSource> sources, String murmur, String fileType, long size, int a, Runnable s, Consumer<FailureCategory> fa) {
			file = f;
			this.sources = sources;
			this.murmur = murmur;
			this.fileType = fileType;
			fileSize = size;
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

		DownloadData(CompletableFuture<Void> f, Path p, String d, long s) {
			future = f;
			file = p;
			activeDomain = d;
			fileSize = s;
		}

		public String getFileName() {
			return file.getFileName().toString();
		}
	}
}
