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
import java.util.zip.GZIPInputStream;

import static pl.skidam.automodpack_core.Constants.*;

public class DownloadManager {

    private static final int MAX_DOWNLOADS_IN_PROGRESS = 5;
    // Actually 3 attempts (0, 1, 2)
    private static final int MAX_DOWNLOAD_ATTEMPTS = 2;

    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(
            MAX_DOWNLOADS_IN_PROGRESS,
            new CustomThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build()
    );

    private DownloadClient downloadClient = null;
    private volatile boolean cancelled = false;

    // TODO remove it, we should assume that if hash matches file is the same so we don't need to separately track path+hash pairs
    // Maps a specific file request (Path + Hash) to a download task
    private final Map<FileInspection.HashPathPair, QueuedDownload> queuedDownloads = new ConcurrentHashMap<>();
    public final Map<FileInspection.HashPathPair, DownloadData> downloadsInProgress = new ConcurrentHashMap<>();

    private long bytesDownloaded = 0;
    private long bytesToDownload = 0;
    private int addedToQueue = 0;
    private int downloaded = 0;

    private final Semaphore semaphore = new Semaphore(0);
    private final SpeedMeter speedMeter = new SpeedMeter(this);

    public DownloadManager() { }

    public DownloadManager(long bytesToDownload) {
        this.bytesToDownload = bytesToDownload;
    }

    public void attachDownloadClient(DownloadClient downloadClient) {
        this.downloadClient = downloadClient;
    }

    /**
     * Queues a file for download.
     * The file will be downloaded to the global store (by hash) and then copied to the 'file' path.
     */
    // TODO dont copy (update self updater to either use store directly or write a method to directly download without store)
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

    /**
     * Main logic for processing a single download request.
     */
    private void processDownloadTask(FileInspection.HashPathPair hashPathPair, QueuedDownload task) {
        LOGGER.info("Processing {} - Hash: {}", task.file.getFileName(), hashPathPair.hash());

        Path storeFile = storeDir.resolve(hashPathPair.hash());
        boolean success = false;
        boolean interrupted = false;

        try {
            // Check if file already exists in Store
            if (verifyFile(storeFile, hashPathPair.hash())) {
                // Increment progress for the cached file so percentage calc remains accurate
                long size = Files.size(storeFile);
                bytesDownloaded += size;
                // Don't update speedMeter for cache hits to avoid fake speed spikes
                success = true;
            } else {
                // Not in store, attempt download
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

    /**
     * Tries to download the file from available sources (URLs or Client).
     * Returns true if downloaded and verified successfully.
     */
    private boolean attemptDownload(FileInspection.HashPathPair hashPathPair, QueuedDownload task, Path storeFile) throws InterruptedException {
        int numberOfIndexes = task.urls.size();
        // Determine which URL to try based on retry count
        int urlIndex = Math.min(task.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);

        String url = (task.urls.size() > urlIndex) ? task.urls.get(urlIndex) : null;

        // Use a temporary file for downloading to ensure atomicity
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

            // Verify the temp file
            if (verifyFile(tempStoreFile, hashPathPair.hash())) {
                // Move temp file to actual store file
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

    /**
     * Handles the cleanup, callbacks, and retries.
     */
    private void cleanupAndFinalize(FileInspection.HashPathPair key, QueuedDownload task, Path storeFile, boolean success, boolean interrupted) {
        downloadsInProgress.remove(key);

        if (success) {
            try {
                // Copy from Store -> Destination
                SmartFileUtils.copyFile(storeFile, task.file);

                downloaded++;
                LOGGER.info("Finished: {} -> {}", storeFile.getFileName(), task.file.getFileName());
                task.successCallback.run();
                semaphore.release();
            } catch (IOException e) {
                LOGGER.error("Failed to copy from store to destination: {}", task.file, e);
                // Technically a failure in the final step, treat as retry-able
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

        // Increase total bytes because we will try to download this amount again
        try {
            // Estimate size if possible, or just ignore (original code logic)
            if (Files.exists(task.file)) bytesToDownload += Files.size(task.file);
        } catch (IOException ignored) {}

        // Wipe destination just in case
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
        var future = downloadClient.downloadFile(hashPathPair.hash().getBytes(StandardCharsets.UTF_8), targetFile, this::updateProgress);
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
                updateProgress(bytesRead);
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

    private void updateProgress(long bytes) {
        bytesDownloaded += bytes;
        speedMeter.addDownloadedBytes(bytes);
    }

    // --- Helpers ---

    private boolean verifyFile(Path file, String expectedHash) {
        if (!Files.exists(file)) return false;
        try {
            return Objects.equals(HashUtils.getHash(file), expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    // --- State Management (Preserved) ---

    public void joinAll() throws InterruptedException {
        semaphore.acquire(addedToQueue);
        if (downloadExecutor.isShutdown()) throw new InterruptedException();
        semaphore.release(addedToQueue);
    }

    public SpeedMeter getSpeedMeter() { return speedMeter; }

    public long getTotalBytesRemaining() { return bytesToDownload - bytesDownloaded; }

    public int getTotalPercentageOfFileSizeDownloaded() {
        if (bytesToDownload == 0) return 0;
        int percentage = (int) (bytesDownloaded * 100 / bytesToDownload);
        return Math.max(0, Math.min(100, percentage));
    }

    public String getStage() { return downloaded + "/" + addedToQueue; }

    public boolean isRunning() { return !downloadExecutor.isShutdown(); }

    public boolean isCanceled() { return cancelled; }

    public void cancelAllAndShutdown() {
        cancelled = true;
        queuedDownloads.clear();
        downloadsInProgress.forEach((key, data) -> {
            data.future.cancel(true);
            SmartFileUtils.executeOrder66(data.file);
        });

        semaphore.release(addedToQueue);
        downloadsInProgress.clear();
        downloaded = 0;
        addedToQueue = 0;

        if (downloadClient != null) downloadClient.close();
        downloadExecutor.shutdown();
    }

    // --- Inner Classes ---

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