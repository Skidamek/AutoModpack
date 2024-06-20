package pl.skidam.automodpack_loader_core.utils;

import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class DownloadManager {
    private static final int MAX_DOWNLOADS_IN_PROGRESS = 5;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final int BUFFER_SIZE = 128 * 1024;
    private final ExecutorService DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(MAX_DOWNLOADS_IN_PROGRESS, new CustomThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build());
    private final Map<HashAndPath, QueuedDownload> queuedDownloads = new ConcurrentHashMap<>();
    public final Map<HashAndPath, DownloadData> downloadsInProgress = new ConcurrentHashMap<>();
    private long bytesDownloaded = 0;
    private long bytesToDownload = 0;
    private int addedToQueue = 0;
    private int downloaded = 0;
    private final Semaphore semaphore = new Semaphore(0);
    public DownloadManager() { }
    public DownloadManager(long bytesToDownload) {
        this.bytesToDownload = bytesToDownload;
    }
    public record HashAndPath(String hash, Path path) { } // Required for cases where there are more files which have the same hash (are the same) but are in different directories/have different names
    // TODO: preferably just download one file and copy it to the other locations

    public void download(Path file, String sha1, Urls urls, Runnable successCallback, Runnable failureCallback) {
        HashAndPath hashAndPath = new HashAndPath(sha1, file);
        if (queuedDownloads.containsKey(hashAndPath))  return;
        queuedDownloads.put(hashAndPath, new QueuedDownload(file, urls, 0, successCallback, failureCallback));
        addedToQueue++;
        downloadNext();
    }

    private void downloadTask(HashAndPath hashAndPath, QueuedDownload queuedDownload) {
        LOGGER.info("Downloading {} - {}", queuedDownload.file.getFileName(), queuedDownload.urls.toString());

        int numberOfIndexes = queuedDownload.urls.numberOfUrls - 1;
        int urlIndex = Math.min(queuedDownload.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);

        String url = queuedDownload.urls.URLs.get(numberOfIndexes - urlIndex).url;

        boolean interrupted = false;

        try {
            downloadFile(url, hashAndPath, queuedDownload);
        } catch (InterruptedException e) {
            CustomFileUtils.forceDelete(queuedDownload.file);
            interrupted = true;
        } catch (SocketTimeoutException e) {
            CustomFileUtils.forceDelete(queuedDownload.file);
            LOGGER.error("Timeout - {} - {}", queuedDownload.file, e);
        } catch (Exception e) {
            CustomFileUtils.forceDelete(queuedDownload.file);
            LOGGER.error("Download failed - {} - {}", queuedDownload.file, e);
        } finally {
            synchronized (downloadsInProgress) {
                downloadsInProgress.remove(hashAndPath);
            }
            boolean failed = true;

            if (Files.exists(queuedDownload.file)) {
                String hash = CustomFileUtils.getHash(queuedDownload.file, "SHA-1").orElse(null);

                if (!Objects.equals(hash, hashAndPath.hash)) {
                    bytesDownloaded -= queuedDownload.file.toFile().length();
                    LOGGER.error("File {} File size: {} File hash: {} Desired file hash: {}", queuedDownload.file, queuedDownload.file.toFile().length(), hash, hashAndPath.hash);
                } else {
                    // Runs on success
                    failed = false;
                    downloaded++;
                    LOGGER.info("Successfully downloaded {} from {}", queuedDownload.file.getFileName(), url);
                    queuedDownload.successCallback.run();
                    semaphore.release();
                }
            }

            if (failed) {

                CustomFileUtils.forceDelete(queuedDownload.file);

                if (interrupted) {
                    return;
                }

                if (queuedDownload.attempts < queuedDownload.urls.numberOfUrls * MAX_DOWNLOAD_ATTEMPTS) {
                    LOGGER.warn("Download of {} failed, retrying!", queuedDownload.file.getFileName());
                    queuedDownload.attempts++;
                    synchronized (queuedDownloads) {
                        queuedDownloads.put(hashAndPath, queuedDownload);
                    }
                } else {
                    LOGGER.error("Download of {} failed!", queuedDownload.file.getFileName());
                    queuedDownload.failureCallback.run();
                    semaphore.release();
                }
            }

            downloadNext();
        }
    }

    private synchronized void downloadNext() {
        if (downloadsInProgress.size() < MAX_DOWNLOADS_IN_PROGRESS && !queuedDownloads.isEmpty()) {
            HashAndPath hashAndPath = queuedDownloads.keySet().stream().findFirst().get();
            QueuedDownload queuedDownload = queuedDownloads.remove(hashAndPath);

            if (queuedDownload == null) {
                return;
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> downloadTask(hashAndPath, queuedDownload), DOWNLOAD_EXECUTOR);

            synchronized (downloadsInProgress) {
                downloadsInProgress.put(hashAndPath, new DownloadData(future, queuedDownload.file));
            }
        }
    }

    private void downloadFile(String urlString, HashAndPath hashAndPath, QueuedDownload queuedDownload) throws IOException, InterruptedException {

        Path outFile = queuedDownload.file;

        if (Files.exists(outFile)) {
            if (Objects.equals(hashAndPath.hash, CustomFileUtils.getHash(outFile, "SHA-1").orElse(null))) {
                return;
            } else {
                CustomFileUtils.forceDelete(outFile);
            }
        }

        if (outFile.getParent() != null) {
            Files.createDirectories(outFile.getParent());
        }

        Files.createFile(outFile);

        URL url = new URL(urlString);
        URLConnection connection = url.openConnection();
        connection.addRequestProperty("Accept-Encoding", "gzip");
        connection.addRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        try (OutputStream outputStream = new FileOutputStream(outFile.toFile());
             InputStream rawInputStream = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
             InputStream inputStream = ("gzip".equals(connection.getHeaderField("Content-Encoding"))) ?
                     new GZIPInputStream(rawInputStream) : rawInputStream) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                bytesDownloaded += bytesRead;
                outputStream.write(buffer, 0, bytesRead);

                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Download got cancelled");
                }
            }
        }
    }


    public void joinAll() throws InterruptedException {
        semaphore.acquire(addedToQueue);

        // Means that download got cancelled, throw exception to don't finish the modpack updater logic
        if (DOWNLOAD_EXECUTOR.isShutdown()) {
            throw new InterruptedException();
        }

        semaphore.release(addedToQueue);
    }

    public long getTotalDownloadSpeed() {
        long totalBytesDownloaded = downloadsInProgress.values().stream()
                .mapToLong(data -> data.file.toFile().length())
                .sum();

        long totalDownloadTimeInSeconds = downloadsInProgress.values().stream()
                .mapToLong(data -> Duration.between(data.startTime, Instant.now()).getSeconds())
                .sum();

        return totalDownloadTimeInSeconds == 0 ? 0 : totalBytesDownloaded / totalDownloadTimeInSeconds;
    }

    public String getTotalDownloadSpeedInReadableFormat(long totalDownloadSpeed) {
        if (totalDownloadSpeed == 0) {
            return "-1";
        }

        // Use the formatSize() method to format the total download speed into a human-readable format
        return addUnitsPerSecond(totalDownloadSpeed);
    }

    public String getTotalETA(long totalDownloadSpeed) {
        long totalBytesRemaining = bytesToDownload - bytesDownloaded;

        return totalDownloadSpeed == 0 ? "0" : totalBytesRemaining / totalDownloadSpeed + "s";
    }

    public String addUnitsPerSecond(long size) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;

        while (size > 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }

        return size + units[unitIndex] + "/s";
    }

    public float getTotalPercentageOfFileSizeDownloaded() {
        return (float) bytesDownloaded / bytesToDownload * 100;
    }

    public String getStage() {
        // files downloaded / files downloaded + queued
        return downloaded + "/" + addedToQueue;
    }

    public boolean isClosed() {
        return DOWNLOAD_EXECUTOR.isShutdown();
    }

    public void cancelAllAndShutdown() {
        queuedDownloads.clear();
        downloadsInProgress.forEach((url, downloadData) -> {
            downloadData.future.cancel(true);
            CustomFileUtils.forceDelete(downloadData.file);
        });

        // TODO Release the number of occupied permits, not all
        semaphore.release(addedToQueue);
        downloadsInProgress.clear();
        downloaded = 0;
        addedToQueue = 0;


        DOWNLOAD_EXECUTOR.shutdownNow();
        try {
            if (!DOWNLOAD_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                DOWNLOAD_EXECUTOR.shutdownNow();
                if (!DOWNLOAD_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                    LOGGER.error("DOWNLOAD EXECUTOR did not terminate");
                }
            }
        } catch (InterruptedException e) {
            DOWNLOAD_EXECUTOR.shutdownNow();
        }
    }

    // TODO re-write it as this consumes too much code lol
    public static class Url {
        private final Map<String, String> headers = new HashMap<>();
        private String url;

        public Url getUrl(String url) {
            this.url = url;
            return this;
        }

        public List<Url> getUrls(List<String> urls) {
            List<Url> urlList = new ArrayList<>();
            urls.forEach((url) -> {
                urlList.add(new Url().getUrl(url));
            });
            return urlList;
        }

        public void addHeader(String headerName, String header) {
            headers.put(headerName, header);
        }
    }

    public static class Urls {
        private final List<Url> URLs = new ArrayList<>(3);
        private int numberOfUrls;

        public Urls addUrl(Url url) {
            URLs.add(url);
            numberOfUrls = URLs.size();
            return this;
        }

        public Urls addAllUrls(List<Url> urls) {
            URLs.addAll(urls);
            numberOfUrls = URLs.size();
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            URLs.forEach((url) -> {
                sb.append(url.url).append(", ");
            });
            sb.delete(sb.length() - 2, sb.length());
            String str = sb.toString();
            return "[" + str + "]";
        }
    }

    public static class QueuedDownload {
        private final Path file;
        private final Urls urls;
        private int attempts;
        private final Runnable successCallback;
        private final Runnable failureCallback;
        public QueuedDownload(Path file, Urls urls, int attempts, Runnable successCallback, Runnable failureCallback) {
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
        public final Instant startTime = Instant.now();

        DownloadData(CompletableFuture<Void> future, Path file) {
            this.future = future;
            this.file = file;
        }

        public String getFileName() {
            return file.getFileName().toString();
        }
    }
}
