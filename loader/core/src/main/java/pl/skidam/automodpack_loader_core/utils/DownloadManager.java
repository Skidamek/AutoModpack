package pl.skidam.automodpack_loader_core.utils;

import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.protocol.DownloadClient;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

import static pl.skidam.automodpack_core.Constants.*;

public class DownloadManager {

    private static final int MAX_DOWNLOADS_IN_PROGRESS = 5;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 2;

    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(
            MAX_DOWNLOADS_IN_PROGRESS,
            new CustomThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build()
    );

    private DownloadClient downloadClient = null;
    private volatile boolean cancelled = false;

    private final Map<FileInspection.HashPathPair, QueuedDownload> queuedDownloads = new ConcurrentHashMap<>();
    public final Map<FileInspection.HashPathPair, DownloadData> downloadsInProgress = new ConcurrentHashMap<>();

    // --- Source Usage Tracking ---
    private final Map<String, Integer> activeDownloadsPerSource = new ConcurrentHashMap<>();

    // --- PROGRESS TRACKING ---
    private final AtomicLong totalBytesToDownload = new AtomicLong(0);
    private final AtomicLong totalBytesDownloaded = new AtomicLong(0);
    private int totalFilesAdded = 0;
    private int downloadedCount = 0;

    private final Semaphore semaphore = new Semaphore(0);
    private final Speedometer speedometer = new Speedometer();

    public DownloadManager() { }

    public DownloadManager(long bytesToDownload) {
        this.totalBytesToDownload.set(bytesToDownload);
        this.speedometer.setExpectedBytes(bytesToDownload);
    }

    public void attachDownloadClient(DownloadClient downloadClient) {
        this.downloadClient = downloadClient;
    }

    public synchronized void download(Path file, String sha1, List<String> urls, long fileSize, Runnable successCallback, Runnable failureCallback) {
        FileInspection.HashPathPair hashPathPair = new FileInspection.HashPathPair(sha1, file);
        if (queuedDownloads.containsKey(hashPathPair)) return;

        QueuedDownload task = new QueuedDownload(file, urls, fileSize, 0, successCallback, failureCallback);
        queuedDownloads.put(hashPathPair, task);
        totalFilesAdded++;
        downloadNext();
    }

    private synchronized void downloadNext() {
        if (downloadsInProgress.size() >= MAX_DOWNLOADS_IN_PROGRESS || queuedDownloads.isEmpty()) {
            return;
        }

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

        // --- 2. DETERMINE SLOT ALLOCATION ---

        int maxBigSlots;

        if (lag > 0.02) {
            // Files are >2% behind.
            // Don't queue any big files anymore. Use all threads for Small Files.
            maxBigSlots = 0;
        } else if (lag > 0.005) {
            // Files are >0.5% behind.
            // Allow only 1 big file to keep bandwidth alive.
            maxBigSlots = 1;
        } else {
            // Balanced / Ahead.
            // Allow natural mix 3:2.
            maxBigSlots = 2;
        }

        // --- 3. COUNT CURRENT STATE ---

        int activeBig = 0;
        int activeSmall = 0;
        for (DownloadData d : downloadsInProgress.values()) {
            if (d.fileSize > avgSize) activeBig++;
            else activeSmall++;
        }

        // --- 4. DECISION ---

        // Do we want a Big file?
        boolean wantBig = (activeBig < maxBigSlots);

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
        if (wantBig && !hasBig) wantBig = false; // Wanted Big, but none left. Take Small.
        if (!wantBig && !hasSmall) wantBig = true; // Wanted Small, but none left. Take Big.

        // --- 6. SELECT BEST FILE ---

        FileInspection.HashPathPair bestKey = null;
        QueuedDownload bestTask = null;
        String bestDomain = null;
        int lowestLoad = Integer.MAX_VALUE;

        for (Map.Entry<FileInspection.HashPathPair, QueuedDownload> entry : queuedDownloads.entrySet()) {
            QueuedDownload task = entry.getValue();
            boolean isBig = task.fileSize > avgSize;

            // FILTER: Strict Type Check
            if (isBig != wantBig) continue;

            String source = predictSource(task);
            int activeInSource = activeDownloadsPerSource.getOrDefault(source, 0);

            // Source Cap (Optional: set to 2 or 3 per source if needed)
            if (activeInSource >= MAX_DOWNLOADS_IN_PROGRESS) continue;

            // Load Balancing: Pick least busy source
            if (activeInSource < lowestLoad) {
                lowestLoad = activeInSource;
                bestKey = entry.getKey();
                bestTask = task;
                bestDomain = source;
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
                    bestDomain = source;
                    break;
                }
            }
        }

        if (bestTask == null) return;

        // --- EXECUTE ---
        queuedDownloads.remove(bestKey);
        activeDownloadsPerSource.merge(bestDomain, 1, Integer::sum);

        final FileInspection.HashPathPair key = bestKey;
        final QueuedDownload task = bestTask;
        final String activeDomain = bestDomain;

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
        int numberOfIndexes = task.urls.size();
        int urlIndex = Math.min(task.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);
        if (task.urls.size() > urlIndex) {
            String url = task.urls.get(urlIndex);
            if (!Objects.equals(url, "host")) {
                return getDomainFromUrl(url);
            }
        }
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
            if (verifyFile(storeFile, hashPathPair.hash())) {
                // CACHE HIT
                long size = Files.size(storeFile);
                totalBytesDownloaded.addAndGet(size);
                // IMPORTANT: Do NOT add cached bytes to Speedometer.
                // It would fake a massive speed spike.

                success = true;
            } else {
                // DOWNLOAD REQUIRED
                success = attemptDownload(hashPathPair, task, storeFile);
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (Exception e) {
            LOGGER.warn("Unexpected error processing {}", task.file, e);
        } finally {
            cleanupAndFinalize(hashPathPair, task, storeFile, success, interrupted);
        }
    }

    private boolean attemptDownload(FileInspection.HashPathPair hashPathPair, QueuedDownload task, Path storeFile) throws InterruptedException {
        int numberOfIndexes = task.urls.size();
        int urlIndex = Math.min(task.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);
        String url = (task.urls.size() > urlIndex) ? task.urls.get(urlIndex) : null;
        Path tempStoreFile = storeDir.resolve(hashPathPair.hash() + ".tmp");

        try {
            if (url != null && !Objects.equals(url, "host") && task.attempts < MAX_DOWNLOAD_ATTEMPTS * numberOfIndexes) {
                httpDownloadFile(url, tempStoreFile);
            } else if (downloadClient != null) {
                hostDownloadFile(hashPathPair, tempStoreFile);
            } else {
                return false;
            }

            if (verifyFile(tempStoreFile, hashPathPair.hash())) {
                SmartFileUtils.moveFile(tempStoreFile, storeFile);
                return true;
            } else {
                LOGGER.warn("Hash mismatch for downloaded file {}", task.file.getFileName());
                SmartFileUtils.executeOrder66(tempStoreFile);
                return false;
            }
        } catch (IOException e) {
            SmartFileUtils.executeOrder66(tempStoreFile);
            return false;
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
            try {
                SmartFileUtils.copyFile(storeFile, task.file);
                downloadedCount++;
                LOGGER.info("Finished: {} -> {}", storeFile.getFileName(), task.file.getFileName());
                task.successCallback.run();
                semaphore.release();
            } catch (IOException e) {
                LOGGER.error("Failed to copy from store to destination: {}", task.file, e);
                handleRetry(key, task, interrupted);
            }
        } else {
            handleRetry(key, task, interrupted);
        }

        if (!interrupted) {
            downloadNext();
        }
    }

    private void handleRetry(FileInspection.HashPathPair key, QueuedDownload task, boolean interrupted) {
        if (interrupted) return;
        try {
            if (Files.exists(task.file)) {
                totalBytesToDownload.addAndGet(Files.size(task.file));
                speedometer.setExpectedBytes(totalBytesToDownload.get());
            }
        } catch (IOException ignored) {}
        SmartFileUtils.executeOrder66(task.file);

        if (task.attempts < (task.urls.size() + 1) * MAX_DOWNLOAD_ATTEMPTS) {
            LOGGER.warn("Retrying download: {}", task.file.getFileName());
            task.attempts++;
            queuedDownloads.put(key, task);
        } else {
            LOGGER.error("Permanently failed to download: {}", task.file.getFileName());
            task.failureCallback.run();
            semaphore.release();
        }
    }

    // --- IO ---

    private void hostDownloadFile(FileInspection.HashPathPair hashPathPair, Path targetFile) throws IOException {
        SmartFileUtils.createParentDirs(targetFile);
        var future = downloadClient.downloadFile(hashPathPair.hash().getBytes(StandardCharsets.UTF_8), targetFile, this::updateNetworkProgress);
        future.join();
    }

    private void httpDownloadFile(String urlString, Path targetFile) throws IOException, InterruptedException {
        SmartFileUtils.createParentDirs(targetFile);
        URLConnection connection = getHttpConnection(urlString);

        try (InputStream rawIn = connection.getInputStream();
             InputStream in = "gzip".equals(connection.getHeaderField("Content-Encoding")) ? new GZIPInputStream(rawIn) : rawIn;
             OutputStream out = new BufferedOutputStream(new FileOutputStream(targetFile.toFile()), 64 * 1024)) {

            byte[] buffer = new byte[NetUtils.DEFAULT_CHUNK_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Download cancelled");
                out.write(buffer, 0, bytesRead);
                updateNetworkProgress(bytesRead);
            }
        }
    }

    private URLConnection getHttpConnection(String urlString) throws IOException {
        URL url = new URL(urlString);
        URLConnection connection = url.openConnection();
        connection.addRequestProperty("Accept-Encoding", "gzip");
        connection.addRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        return connection;
    }

    private void updateNetworkProgress(long bytes) {
        totalBytesDownloaded.addAndGet(bytes);
        speedometer.addBytes(bytes);
    }

    private boolean verifyFile(Path file, String expectedHash) {
        if (!Files.exists(file)) return false;
        try { return Objects.equals(HashUtils.getHash(file), expectedHash); } catch (Exception e) { return false; }
    }

    public void joinAll() throws InterruptedException {
        semaphore.acquire(totalFilesAdded);
        if (downloadExecutor.isShutdown()) throw new InterruptedException();
        semaphore.release(totalFilesAdded);
    }

    // --- UI Helpers ---

    public long getDownloadSpeed() { return speedometer.getSpeed(); }
    public long getETA() { return speedometer.getETA(); }

    public double getPrecisePercentage() {
        long total = totalBytesToDownload.get();
        if (total == 0) return 0.0;
        double pc = (double) totalBytesDownloaded.get() * 100.0 / total;
        return Math.max(0.0, Math.min(100.0, pc));
    }

    public String getStage() { return downloadedCount + "/" + totalFilesAdded; }
    public boolean isRunning() { return !downloadExecutor.isShutdown(); }

    public void cancelAllAndShutdown() {
        cancelled = true;
        queuedDownloads.clear();
        downloadsInProgress.forEach((k, v) -> {
            v.future.cancel(true);
            SmartFileUtils.executeOrder66(v.file);
        });
        semaphore.release(totalFilesAdded);
        downloadsInProgress.clear();
        downloadedCount = 0;
        if (downloadClient != null) downloadClient.close();
        downloadExecutor.shutdown();
    }

    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    // --- Inner Classes ---

    public static class QueuedDownload {
        public final Path file;
        public final List<String> urls;
        public final long fileSize;
        public int attempts;
        public final Runnable successCallback;
        public final Runnable failureCallback;

        public QueuedDownload(Path f, List<String> u, long size, int a, Runnable s, Runnable fa) {
            file = f; urls = u; fileSize = size; attempts = a; successCallback = s; failureCallback = fa;
        }
    }

    public static class DownloadData {
        public CompletableFuture<Void> future;
        public Path file;
        public String activeDomain;
        public long fileSize;

        DownloadData(CompletableFuture<Void> f, Path p, String d, long s) {
            future = f; file = p; activeDomain = d; fileSize = s;
        }
        public String getFileName() { return file.getFileName().toString(); }
    }
}