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

import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.LocalStorageException;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

public class DownloadManager {

	public enum FailureCategory {
		REMOTE_SOURCE,
		LOCAL_STORAGE,
		CANCELLED
	}

	public record AcquisitionResult(boolean success, FailureCategory failureCategory) {}

	private static final int MAX_DOWNLOADS_IN_PROGRESS = 5;
	private static final int MAX_DOWNLOAD_ATTEMPTS = 2;

	private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(MAX_DOWNLOADS_IN_PROGRESS,
			new CustomThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build());

	private final HttpFileDownloader httpDownloader = new HttpFileDownloader();
	private DownloadClient downloadClient = null;

	private volatile boolean cancelled = false;

	// --- QUEUES ---
	private final Map<FileInspection.HashPathPair, QueuedDownload> queuedDownloads = new ConcurrentHashMap<>();
	public final Map<FileInspection.HashPathPair, DownloadData> downloadsInProgress = new ConcurrentHashMap<>();
	private final Map<FileInspection.HashPathPair, Path> activeTemporaryFiles = new ConcurrentHashMap<>();
	private final Map<FileInspection.HashPathPair, AcquisitionResult> acquisitionResults = new ConcurrentHashMap<>();

	private final Map<String, Integer> activeDownloadsPerSource = new ConcurrentHashMap<>();

	// --- PROGRESS TRACKING ---
	private final AtomicLong totalBytesToDownload = new AtomicLong(0);
	private final AtomicLong totalBytesDownloaded = new AtomicLong(0);
	private int totalFilesAdded = 0;
	private int downloadedCount = 0;

	private final Semaphore semaphore = new Semaphore(0);
	private final Speedometer speedometer = new Speedometer();

	public DownloadManager() {}

	public DownloadManager(long bytesToDownload) {
		this.totalBytesToDownload.set(bytesToDownload);
		this.speedometer.setExpectedBytes(bytesToDownload);
	}

	public void attachDownloadClient(DownloadClient downloadClient) {
		this.downloadClient = downloadClient;
	}

	public synchronized void download(Path file, String sha1, List<DownloadSource> sources, long fileSize, Runnable successCallback, Runnable failureCallback) {
		download(file, sha1, sources, fileSize, successCallback, ignored -> failureCallback.run());
	}

	public synchronized void download(Path file, String sha1, List<DownloadSource> sources, long fileSize, Runnable successCallback,
			Consumer<FailureCategory> failureCallback) {
		FileInspection.HashPathPair hashPathPair = new FileInspection.HashPathPair(sha1, file);
		if (queuedDownloads.containsKey(hashPathPair)) return;

		QueuedDownload task = new QueuedDownload(file, sources, fileSize, 0, successCallback, failureCallback);
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

		CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
			try {
				processDownloadTask(key, task);
			} catch (Exception e) {
				LOGGER.error("Fatal error executing download task for {}", task.file.getFileName(), e);
			}
		}, downloadExecutor);

		downloadsInProgress.put(key, new DownloadData(future, task.file, activeDomain, task.fileSize));
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
		Path storeFile = storeDir.resolve(hashPathPair.hash());
		boolean success = false;
		boolean interrupted = false;

		try {
			if (SmartFileUtils.isValidFile(storeFile, task.fileSize, hashPathPair.hash())) {
				// CACHE HIT
				totalBytesDownloaded.addAndGet(task.fileSize);
				// IMPORTANT: Do NOT add cached bytes to Speedometer.
				// It would fake a massive speed spike.

				success = true;
			} else {
				// DOWNLOAD REQUIRED. A corrupt object is never a cache hit.
				if (Files.exists(storeFile)) Files.delete(storeFile);
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
		int numberOfIndexes = task.sources.size();
		int sourceIndex = Math.min(task.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);
		DownloadSource source = (task.sources.size() > sourceIndex) ? task.sources.get(sourceIndex) : null;
		Path tempStoreFile = null;

		try {
			try {
				Files.createDirectories(storeDir);
				tempStoreFile = Files.createTempFile(storeDir, "." + hashPathPair.hash() + ".", ".tmp");
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
				if (source != null && source.provider() == DownloadSource.Provider.CURSEFORGE && e.statusCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
					LOGGER.warn("CurseForge rejected the download API key with HTTP 401; trying the next source");
					task.attempts = (sourceIndex + 1) * MAX_DOWNLOAD_ATTEMPTS - 1;
				}
				return false;
			} catch (IOException e) {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				LOGGER.warn("Remote source failed for CAS object {}", hashPathPair.hash(), e);
				return false;
			}

			if (!SmartFileUtils.isValidFile(tempStoreFile, task.fileSize, hashPathPair.hash())) {
				task.lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				LOGGER.warn("Size or hash mismatch for downloaded file {}", task.file.getFileName());
				return false;
			}
			try {
				SmartFileUtils.promoteVerifiedAtomic(tempStoreFile, storeFile, task.fileSize, hashPathPair.hash());
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

	private void cleanupAndFinalize(FileInspection.HashPathPair key, QueuedDownload task, Path storeFile, boolean success, boolean interrupted) {
		DownloadData data = downloadsInProgress.remove(key);

		if (data != null && data.activeDomain != null) {
			synchronized (this) {
				activeDownloadsPerSource.compute(data.activeDomain, (k, v) -> (v == null || v <= 1) ? null : v - 1);
			}
		}

		if (success) {
			downloadedCount++;
			acquisitionResults.put(key, new AcquisitionResult(true, null));
			LOGGER.info("Acquired CAS object {} for {}", storeFile.getFileName(), task.file.getFileName());
			task.successCallback.run();
			semaphore.release();
		} else {
			handleRetry(key, task, interrupted);
		}

		if (!interrupted) downloadNext();
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
			task.failureCallback.accept(category);
			semaphore.release();
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
		if (downloadClient != null) downloadClient.close();
		downloadExecutor.shutdown();
	}

	public Map<FileInspection.HashPathPair, AcquisitionResult> getAcquisitionResults() {
		return Map.copyOf(acquisitionResults);
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}

	// --- Inner Classes ---

	public static class QueuedDownload {
		public final Path file;
		public final List<DownloadSource> sources;
		public final long fileSize;
		public int attempts;
		public final Runnable successCallback;
		public final Consumer<FailureCategory> failureCallback;
		public FailureCategory lastFailureCategory;

		public QueuedDownload(Path f, List<DownloadSource> sources, long size, int a, Runnable s, Consumer<FailureCategory> fa) {
			file = f;
			this.sources = sources;
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
