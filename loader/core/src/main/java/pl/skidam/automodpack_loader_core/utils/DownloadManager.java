package pl.skidam.automodpack_loader_core.utils;

import pl.skidam.automodpack_core.protocol.client.Client;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.protocol.client.backends.DownloadClient;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class DownloadManager {
    private static final int MAX_DOWNLOADS_IN_PROGRESS = 5;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 2; // its actually 3, but we start from 0
    private static final int BUFFER_SIZE = 128 * 1024;
    private final ExecutorService DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(MAX_DOWNLOADS_IN_PROGRESS, new CustomThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build());
    private Client downloadClient = null;
    private boolean cancelled = false;
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
    // TODO: make caching system which detects if the same file was downloaded before and if so copy it instead of downloading again

    public void attachDownloadClient(Client downloadClient) {
         this.downloadClient = downloadClient;
    }

    public void download(Path file, String sha1, List<String> urls, Runnable successCallback, Runnable failureCallback) {
        FileInspection.HashPathPair hashPathPair = new FileInspection.HashPathPair(sha1, file);
        if (queuedDownloads.containsKey(hashPathPair)) return;
        queuedDownloads.put(hashPathPair, new QueuedDownload(file, urls, 0, successCallback, failureCallback));
        addedToQueue++;
        downloadNext();
    }

    private void downloadTask(FileInspection.HashPathPair hashPathPair, QueuedDownload queuedDownload) throws Exception {
        LOGGER.info("Downloading {} - {}", queuedDownload.file.getFileName(), queuedDownload.urls);

        int numberOfIndexes = queuedDownload.urls.size();
        int urlIndex = Math.min(queuedDownload.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);
        String url = "host";
        if (queuedDownload.urls.size() > urlIndex) { // avoids IndexOutOfBoundsException
            url = queuedDownload.urls.get(urlIndex);
        }
        boolean interrupted = false;

        try {
            if (url != null && !Objects.equals(url, "host") && queuedDownload.attempts < MAX_DOWNLOAD_ATTEMPTS * numberOfIndexes) {
                httpDownloadFile(url, hashPathPair, queuedDownload);
            } else if (downloadClient != null) {
                hostDownloadFile(hashPathPair, queuedDownload);
            } else {
                LOGGER.error("No download client attached, can't download file - {}", queuedDownload.file.getFileName());
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } catch (SocketTimeoutException e) {
            LOGGER.warn("Timeout - {} - {} - {}", queuedDownload.file, e, e.fillInStackTrace());
        } catch (Exception e) {
            LOGGER.warn("Error while downloading file - {} - {} - {}", queuedDownload.file, e, e.fillInStackTrace());
        } finally {
            synchronized (downloadsInProgress) {
                downloadsInProgress.remove(hashPathPair);
            }

            boolean failed = true;

            if (Files.exists(queuedDownload.file)) {
                String hash = CustomFileUtils.getHash(queuedDownload.file);

                if (Objects.equals(hash, hashPathPair.hash())) {
                    // Runs on success
                    failed = false;
                    downloaded++;
                    LOGGER.info("Successfully downloaded {} from {}", queuedDownload.file.getFileName(), url);
                    queuedDownload.successCallback.run();
                    semaphore.release();
                }
            }

            if (failed) {
                bytesToDownload += queuedDownload.file.toFile().length(); // Add size of the whole file again because we will try to download it again
                CustomFileUtils.executeOrder66(queuedDownload.file);

                if (!interrupted) {
                    if (queuedDownload.attempts < (numberOfIndexes + 1) * MAX_DOWNLOAD_ATTEMPTS) {
                        LOGGER.warn("Download of {} failed, retrying!", queuedDownload.file.getFileName());
                        queuedDownload.attempts++;
                        synchronized (queuedDownloads) {
                            queuedDownloads.put(hashPathPair, queuedDownload);
                        }
                    } else {
                        LOGGER.error("Download of {} failed!", queuedDownload.file.getFileName());
                        queuedDownload.failureCallback.run();
                        semaphore.release();
                    }
                }
            }

            if (!interrupted) {
                downloadNext();
            }
        }
    }

    private synchronized void downloadNext() {
        if (downloadsInProgress.size() < MAX_DOWNLOADS_IN_PROGRESS && !queuedDownloads.isEmpty()) {
            FileInspection.HashPathPair hashAndPath = queuedDownloads.keySet().stream().findFirst().get();
            QueuedDownload queuedDownload = queuedDownloads.remove(hashAndPath);

            if (queuedDownload == null) {
                return;
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    downloadTask(hashAndPath, queuedDownload);
                } catch (Exception e) {
                    LOGGER.error("Error while downloading file - {}", queuedDownload.file.getFileName(), e);
                }
            }, DOWNLOAD_EXECUTOR);

            synchronized (downloadsInProgress) {
                downloadsInProgress.put(hashAndPath, new DownloadData(future, queuedDownload.file));
            }
        }
    }

    private void hostDownloadFile(FileInspection.HashPathPair hashPathPair, QueuedDownload queuedDownload) throws IOException, InterruptedException {
        Path outFile = queuedDownload.file;

        if (Files.exists(outFile)) {
            if (Objects.equals(hashPathPair.hash(), CustomFileUtils.getHash(outFile))) {
                return;
            } else {
                CustomFileUtils.executeOrder66(outFile);
            }
        }

        CustomFileUtils.setupFilePaths(outFile);

        var future = downloadClient.downloadFile(hashPathPair.hash().getBytes(StandardCharsets.UTF_8), outFile, (bytes) -> {
            bytesDownloaded += bytes;
            speedMeter.addDownloadedBytes(bytes);
        });
        future.join();
    }

    private void httpDownloadFile(String url, FileInspection.HashPathPair hashPathPair, QueuedDownload queuedDownload) throws IOException, InterruptedException {

        Path outFile = queuedDownload.file;

        if (Files.exists(outFile)) {
            if (Objects.equals(hashPathPair.hash(), CustomFileUtils.getHash(outFile))) {
                return;
            } else {
                CustomFileUtils.executeOrder66(outFile);
            }
        }

        CustomFileUtils.setupFilePaths(outFile);

        URLConnection connection = getHttpConnection(url);

        try (OutputStream outputStream = new FileOutputStream(outFile.toFile());
             InputStream rawInputStream = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
             InputStream inputStream = ("gzip".equals(connection.getHeaderField("Content-Encoding"))) ?
                     new GZIPInputStream(rawInputStream) : rawInputStream) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                bytesDownloaded += bytesRead;
                speedMeter.addDownloadedBytes(bytesRead);
                outputStream.write(buffer, 0, bytesRead);

                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Download got cancelled");
                }
            }
        }
    }

    private URLConnection getHttpConnection(String url) throws IOException {

        LOGGER.info("Downloading from {}", url);

        URL connectionUrl = new URL(url);
        URLConnection connection = connectionUrl.openConnection();
        connection.addRequestProperty("Accept-Encoding", "gzip");
        connection.addRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        return connection;
    }


    public void joinAll() throws InterruptedException {
        semaphore.acquire(addedToQueue);

        // Means that download got cancelled, throw exception to don't finish the modpack updater logic
        if (DOWNLOAD_EXECUTOR.isShutdown()) {
            throw new InterruptedException();
        }

        semaphore.release(addedToQueue);
    }

    public SpeedMeter getSpeedMeter() {
        return speedMeter;
    }

    public long getTotalBytesRemaining() {
        return bytesToDownload - bytesDownloaded;
    }

    public int getTotalPercentageOfFileSizeDownloaded() {
        if (bytesDownloaded == 0 || bytesToDownload == 0) {
            return 0;
        }

        int percentage = (int) (bytesDownloaded * 100 / bytesToDownload);
        return Math.max(0, Math.min(100, percentage));
    }

    public String getStage() {
        // files downloaded / files downloaded + queued
        return downloaded + "/" + addedToQueue;
    }

    public boolean isRunning() {
        return !DOWNLOAD_EXECUTOR.isShutdown();
    }

    public boolean isCanceled() {
        return cancelled;
    }

    public void cancelAllAndShutdown() {
        cancelled = true;
        queuedDownloads.clear();
        downloadsInProgress.forEach((url, downloadData) -> {
            downloadData.future.cancel(true);
            CustomFileUtils.executeOrder66(downloadData.file);
        });

        // TODO Release the number of occupied permits, not all
        semaphore.release(addedToQueue);
        downloadsInProgress.clear();
        downloaded = 0;
        addedToQueue = 0;

        if (downloadClient != null) {
            downloadClient.close();
        }

        DOWNLOAD_EXECUTOR.shutdown();
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
