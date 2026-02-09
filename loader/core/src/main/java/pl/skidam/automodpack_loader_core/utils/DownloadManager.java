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

    // Stats
    private final AtomicLong totalBytesDownloaded = new AtomicLong(0); // For Progress Bar (Includes Cache)
    private final AtomicLong totalBytesToDownload = new AtomicLong(0); // For Progress Bar
    private int addedToQueue = 0;
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

    public void download(Path file, String sha1, List<String> urls, Runnable successCallback, Runnable failureCallback) {
        FileInspection.HashPathPair hashPathPair = new FileInspection.HashPathPair(sha1, file);

        if (queuedDownloads.containsKey(hashPathPair)) return;

        queuedDownloads.put(hashPathPair, new QueuedDownload(file, urls, 0, successCallback, failureCallback));
        addedToQueue++;

        downloadNext();
    }

    private synchronized void downloadNext() {
        if (downloadsInProgress.size() >= MAX_DOWNLOADS_IN_PROGRESS || queuedDownloads.isEmpty()) {
            return;
        }

        var entry = queuedDownloads.entrySet().iterator().next();
        FileInspection.HashPathPair key = entry.getKey();
        QueuedDownload task = queuedDownloads.remove(key);

        if (task == null) return;

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                processDownloadTask(key, task);
            } catch (Exception e) {
                LOGGER.error("Fatal error executing download task for {}", task.file.getFileName(), e);
            }
        }, downloadExecutor);

        downloadsInProgress.put(key, new DownloadData(future, task.file));
    }

    private void processDownloadTask(FileInspection.HashPathPair hashPathPair, QueuedDownload task) {
        LOGGER.info("Processing {} - Hash: {}", task.file.getFileName(), hashPathPair.hash());

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
                LOGGER.error("No valid source found for {}", task.file.getFileName());
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
            LOGGER.warn("Download I/O error for {}: {}", task.file.getFileName(), e.getMessage());
            SmartFileUtils.executeOrder66(tempStoreFile);
            return false;
        }
    }

    private void cleanupAndFinalize(FileInspection.HashPathPair key, QueuedDownload task, Path storeFile, boolean success, boolean interrupted) {
        downloadsInProgress.remove(key);

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
                long size = Files.size(task.file);
                // If we retry, we expect to download this size again
                totalBytesToDownload.addAndGet(size);
                speedometer.setExpectedBytes(totalBytesToDownload.get());
            }
        } catch (IOException ignored) {}

        SmartFileUtils.executeOrder66(task.file);

        int numberOfIndexes = task.urls.size();
        int maxAttempts = (numberOfIndexes + 1) * MAX_DOWNLOAD_ATTEMPTS;

        if (task.attempts < maxAttempts) {
            LOGGER.warn("Retrying download: {}", task.file.getFileName());
            task.attempts++;
            queuedDownloads.put(key, task);
        } else {
            LOGGER.error("Permanently failed to download: {}", task.file.getFileName());
            task.failureCallback.run();
            semaphore.release();
        }
    }

    // --- Download Implementations ---

    private void hostDownloadFile(FileInspection.HashPathPair hashPathPair, Path targetFile) throws IOException {
        SmartFileUtils.createParentDirs(targetFile);
        var future = downloadClient.downloadFile(hashPathPair.hash().getBytes(StandardCharsets.UTF_8), targetFile, this::updateNetworkProgress);
        future.join();
    }

    private void httpDownloadFile(String urlString, Path targetFile) throws IOException, InterruptedException {
        SmartFileUtils.createParentDirs(targetFile);
        LOGGER.info("Downloading from {}", urlString);

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

    /**
     * Updates counters ONLY for actual network traffic.
     */
    private void updateNetworkProgress(long bytes) {
        totalBytesDownloaded.addAndGet(bytes);
        speedometer.addBytes(bytes);
    }

    private boolean verifyFile(Path file, String expectedHash) {
        if (!Files.exists(file)) return false;
        try {
            return Objects.equals(HashUtils.getHash(file), expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    // --- Getters & Control ---

    public void joinAll() throws InterruptedException {
        semaphore.acquire(addedToQueue);
        if (downloadExecutor.isShutdown()) throw new InterruptedException();
        semaphore.release(addedToQueue);
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

    public String getStage() { return downloadedCount + "/" + addedToQueue; }

    public boolean isRunning() { return !downloadExecutor.isShutdown(); }

    public void cancelAllAndShutdown() {
        cancelled = true;
        queuedDownloads.clear();
        downloadsInProgress.forEach((key, data) -> {
            data.future.cancel(true);
            SmartFileUtils.executeOrder66(data.file);
        });

        semaphore.release(addedToQueue);
        downloadsInProgress.clear();
        downloadedCount = 0;
        addedToQueue = 0;

        if (downloadClient != null) downloadClient.close();
        downloadExecutor.shutdown();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public static class QueuedDownload {
        private final Path file;
        private final List<String> urls;
        private int attempts;
        private final Runnable successCallback;
        private final Runnable failureCallback;

        public QueuedDownload(Path file, List<String> urls, int attempts, Runnable successCallback, Runnable failureCallback) {
            this.file = file;
            this.urls = urls;
            this.attempts = attempts;
            this.successCallback = successCallback;
            this.failureCallback = failureCallback;
        }
    }

    public static class DownloadData {
        public CompletableFuture<Void> future;
        public Path file;

        DownloadData(CompletableFuture<Void> future, Path file) {
            this.future = future;
            this.file = file;
        }

        public String getFileName() {
            return file.getFileName().toString();
        }
    }
}