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

import pl.skidam.automodpack_core.protocol.DownloadBatchProtocol;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.DownloadFailure;
import pl.skidam.automodpack_core.protocol.DownloadRequest;
import pl.skidam.automodpack_core.protocol.DownloadResult;
import pl.skidam.automodpack_core.protocol.LocalStorageException;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;

public class DownloadManager {

	public enum FailureCategory {
		REMOTE_SOURCE,
		LOCAL_STORAGE,
		CANCELLED
	}

	public record AcquisitionResult(boolean success, FailureCategory failureCategory) {}

	private static final int MAX_DOWNLOADS_IN_PROGRESS = 5;
	private static final int MAX_DOWNLOAD_ATTEMPTS = 2;
	private static final String INTERNAL_SOURCE = "internal_client";

	private final ExecutorService downloadExecutor;

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
	private final ClientStorage storage;

	public DownloadManager() {
		this(0, ClientStorage.open(GameDirectory.current()));
	}

	public DownloadManager(long bytesToDownload) {
		this(bytesToDownload, ClientStorage.open(GameDirectory.current()));
	}

	public DownloadManager(long bytesToDownload, ClientStorage storage) {
		this(bytesToDownload, storage, Executors.newFixedThreadPool(MAX_DOWNLOADS_IN_PROGRESS,
				new CustomThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build()));
	}

	DownloadManager(long bytesToDownload, ClientStorage storage, ExecutorService downloadExecutor) {
		this.totalBytesToDownload.set(bytesToDownload);
		this.speedometer.setExpectedBytes(bytesToDownload);
		this.storage = Objects.requireNonNull(storage, "storage");
		this.downloadExecutor = Objects.requireNonNull(downloadExecutor, "downloadExecutor");
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
		if (!INTERNAL_SOURCE.equals(predictSource(task)) || downloadClient == null) downloadNext();
	}

	private synchronized void downloadNext() {
		if (cancelled || downloadExecutor.isShutdown() || downloadsInProgress.size() >= MAX_DOWNLOADS_IN_PROGRESS || queuedDownloads.isEmpty()) return;

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

		int availableSlots = MAX_DOWNLOADS_IN_PROGRESS - downloadsInProgress.size();
		List<Map.Entry<FileInspection.HashPathPair, QueuedDownload>> selectedEntries;
		if (INTERNAL_SOURCE.equals(bestSource)) {
			selectedEntries = queuedDownloads.entrySet().stream().filter(entry -> INTERNAL_SOURCE.equals(predictSource(entry.getValue())))
					.filter(entry -> activeDownloadsPerSource.getOrDefault(INTERNAL_SOURCE, 0) < MAX_DOWNLOADS_IN_PROGRESS)
					.sorted(Comparator.comparingLong((Map.Entry<FileInspection.HashPathPair, QueuedDownload> entry) -> entry.getValue().fileSize)
							.thenComparing(entry -> entry.getKey().hash()))
					.limit(Math.min(availableSlots, DownloadBatchProtocol.MAX_ITEM_COUNT)).toList();
		} else {
			selectedEntries = List.of(Map.entry(bestKey, bestTask));
		}
		if (selectedEntries.isEmpty()) return;

		List<ScheduledDownload> scheduled = new ArrayList<>(selectedEntries.size());
		for (Map.Entry<FileInspection.HashPathPair, QueuedDownload> entry : selectedEntries) {
			FileInspection.HashPathPair key = entry.getKey();
			QueuedDownload task = entry.getValue();
			if (!queuedDownloads.remove(key, task)) continue;
			String activeDomain = predictSource(task);
			activeDownloadsPerSource.merge(activeDomain, 1, Integer::sum);
			CompletableFuture<Void> future = new CompletableFuture<>();
			DownloadData data = new DownloadData(future, task.file, activeDomain, task.fileSize);
			downloadsInProgress.put(key, data);
			scheduled.add(new ScheduledDownload(scheduled.size() + 1, key, task, data));
		}
		if (scheduled.isEmpty()) return;

		LOGGER.info("Queueing {} download{}", scheduled.size(), scheduled.size() == 1 ? "" : "s");
		try {
			downloadExecutor.execute(() -> {
				Throwable fatalError = null;
				try {
					if (scheduled.size() == 1) processDownloadTask(scheduled.get(0).key(), scheduled.get(0).task());
					else processHostBatch(scheduled);
				} catch (Throwable error) {
					fatalError = error;
					LOGGER.error("Fatal error executing download batch", error);
				} finally {
					for (ScheduledDownload item : scheduled) {
						if (fatalError == null) item.data().future.complete(null);
						else item.data().future.completeExceptionally(fatalError);
					}
				}
			});
		} catch (RuntimeException error) {
			for (ScheduledDownload item : scheduled) {
				downloadsInProgress.remove(item.key());
				activeDownloadsPerSource.compute(item.data().activeDomain, (source, count) -> (count == null || count <= 1) ? null : count - 1);
				item.data().future.completeExceptionally(error);
			}
			throw error;
		}
	}

	private String predictSource(QueuedDownload task) {
		int numberOfIndexes = task.sources.size();
		int sourceIndex = Math.min(task.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);
		if (task.sources.size() > sourceIndex) return getDomainFromUrl(task.sources.get(sourceIndex).url());
		return INTERNAL_SOURCE;
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
		Path storeFile = storage.objectsDirectory().resolve(hashPathPair.hash());
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

	private void processHostBatch(List<ScheduledDownload> scheduled) {
		List<HostBatchItem> items = scheduled.stream().map(item -> new HostBatchItem(item, storage.objectsDirectory().resolve(item.key().hash()))).toList();
		try {
			for (HostBatchItem item : items) {
				if (FileIntegrity.matches(item.storeFile, item.task().fileSize, item.key().hash())) {
					totalBytesDownloaded.addAndGet(item.task().fileSize);
					item.success = true;
					continue;
				}
				try {
					ImmutableFiles.deleteIfExists(item.storeFile);
					item.temporaryFile = Files.createTempFile(storage.incomingDirectory(), "." + item.key().hash() + ".", ".tmp");
					activeTemporaryFiles.put(item.key(), item.temporaryFile);
					item.request = new DownloadRequest(item.scheduled().itemId(), item.key().hash(), item.temporaryFile, item.task().fileSize, this::updateNetworkProgress);
				} catch (IOException e) {
					item.task().lastFailureCategory = FailureCategory.LOCAL_STORAGE;
					LOGGER.warn("Failed to create temporary CAS object {}", item.key().hash(), e);
				}
			}

			List<DownloadRequest> requests = items.stream().map(HostBatchItem::request).filter(Objects::nonNull).toList();
			if (!requests.isEmpty()) {
				if (downloadClient == null) {
					for (HostBatchItem item : items) if (item.request != null) item.task().lastFailureCategory = FailureCategory.REMOTE_SOURCE;
				} else {
					try {
						List<DownloadResult> results = downloadClient.downloadBatch(requests).get();
						Map<Integer, HostBatchItem> itemsById = new HashMap<>();
						for (HostBatchItem item : items) if (item.request != null) itemsById.put(item.request.itemId(), item);
						for (DownloadResult result : results) {
							HostBatchItem item = itemsById.remove(result.request().itemId());
							if (item == null) continue;
							if (result.success()) item.transportSuccess = true;
							else applyBatchFailure(item, result.failure().orElseThrow());
						}
						for (HostBatchItem item : itemsById.values()) item.task().lastFailureCategory = FailureCategory.REMOTE_SOURCE;
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						for (HostBatchItem item : items) if (item.request != null) item.interrupted = true;
					} catch (ExecutionException | CancellationException e) {
						for (HostBatchItem item : items) if (item.request != null) item.task().lastFailureCategory = FailureCategory.REMOTE_SOURCE;
					}
				}
			}

			for (HostBatchItem item : items) {
				if (item.success || item.interrupted || item.task().lastFailureCategory != null || !item.transportSuccess) continue;
				if (!FileIntegrity.matches(item.temporaryFile, item.task().fileSize, item.key().hash())) {
					item.task().lastFailureCategory = FailureCategory.REMOTE_SOURCE;
					LOGGER.warn("Size or hash mismatch for downloaded file {}", item.task().file.getFileName());
					continue;
				}
				try {
					VerifiedFileTransfer.promoteAtomic(item.temporaryFile, item.storeFile, item.task().fileSize, item.key().hash());
					item.temporaryFile = null;
					item.task().lastFailureCategory = null;
					item.success = true;
				} catch (IOException e) {
					item.task().lastFailureCategory = FailureCategory.LOCAL_STORAGE;
					LOGGER.warn("Failed to persist verified CAS object {}", item.key().hash(), e);
				}
			}
		} catch (RuntimeException e) {
			for (HostBatchItem item : items) {
				if (!item.success && !item.interrupted && item.task().lastFailureCategory == null) item.task().lastFailureCategory = FailureCategory.LOCAL_STORAGE;
			}
			LOGGER.error("Failed to process host download batch", e);
		} finally {
			for (HostBatchItem item : items) {
				activeTemporaryFiles.remove(item.key());
				if (item.temporaryFile != null) {
					try {
						Files.deleteIfExists(item.temporaryFile);
					} catch (IOException ignored) {
					}
				}
				try {
					cleanupAndFinalize(item.key(), item.task(), item.storeFile, item.success, item.interrupted, false);
				} catch (Throwable error) {
					LOGGER.error("Failed to finalize host download {}", item.task().file, error);
				}
			}
			if (!cancelled) downloadNext();
		}
	}

	private void applyBatchFailure(HostBatchItem item, DownloadFailure failure) {
		if (failure.kind() == DownloadFailure.Kind.CANCELLED) {
			item.interrupted = true;
			return;
		}
		item.task().lastFailureCategory = failure.kind() == DownloadFailure.Kind.LOCAL_STORAGE ? FailureCategory.LOCAL_STORAGE : FailureCategory.REMOTE_SOURCE;
	}

	private boolean attemptDownload(FileInspection.HashPathPair hashPathPair, QueuedDownload task, Path storeFile) throws InterruptedException {
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

	private void cleanupAndFinalize(FileInspection.HashPathPair key, QueuedDownload task, Path storeFile, boolean success, boolean interrupted) {
		cleanupAndFinalize(key, task, storeFile, success, interrupted, true);
	}

	private void cleanupAndFinalize(FileInspection.HashPathPair key, QueuedDownload task, Path storeFile, boolean success, boolean interrupted, boolean scheduleNext) {
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
			if (!interrupted && scheduleNext) downloadNext();
		}
	}

	private void handleRetry(FileInspection.HashPathPair key, QueuedDownload task, boolean interrupted) {
		if (interrupted || cancelled) {
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
		// Host-only work is coalesced until the caller has finished enqueueing its manifest.
		downloadNext();
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
		if (downloadClient != null) downloadClient.close();
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
		downloadExecutor.shutdownNow();
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

	private record ScheduledDownload(int itemId, FileInspection.HashPathPair key, QueuedDownload task, DownloadData data) {}

	private static final class HostBatchItem {
		private final ScheduledDownload scheduled;
		private final FileInspection.HashPathPair key;
		private final QueuedDownload task;
		private final Path storeFile;
		private Path temporaryFile;
		private DownloadRequest request;
		private boolean transportSuccess;
		private boolean success;
		private boolean interrupted;

		private HostBatchItem(ScheduledDownload scheduled, Path storeFile) {
			this.scheduled = scheduled;
			this.key = scheduled.key();
			this.task = scheduled.task();
			this.storeFile = storeFile;
		}

		private ScheduledDownload scheduled() {
			return scheduled;
		}

		private FileInspection.HashPathPair key() {
			return key;
		}

		private QueuedDownload task() {
			return task;
		}

		private DownloadRequest request() {
			return request;
		}
	}

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
