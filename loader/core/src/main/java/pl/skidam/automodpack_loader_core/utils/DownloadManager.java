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
    private final Map<String, QueuedDownload> queuedDownloads = new ConcurrentHashMap<>();
    public final Map<String, DownloadData> downloadsInProgress = new ConcurrentHashMap<>();
    private long bytesDownloaded = 0;
    private long bytesToDownload = 0;
    private int addedToQueue = 0;
    private int downloaded = 0;
    private final Semaphore semaphore = new Semaphore(0);
    public DownloadManager() { }

    public DownloadManager(long bytesToDownload) {
        this.bytesToDownload = bytesToDownload;
    }


    public void download(Path file, String sha1, Urls urls, Runnable successCallback, Runnable failureCallback) {
        if (!queuedDownloads.containsKey(sha1)) {
            queuedDownloads.put(sha1, new QueuedDownload(file, urls, 0, successCallback, failureCallback));
            addedToQueue++;
            downloadNext();
        }
    }

    private void downloadTask(String sha1, QueuedDownload queuedDownload) {

        // Firstly get the last URL from the list, if the download failed 3 times and we get one more url, then get the seccond url if the download failed 6 times (3 times for each url) then get the first url (0 index
//        String url = queuedDownload.urls.URLs.get(queuedDownload.urls.URLs.size() - 1).url;
//        if (queuedDownload.attempts > MAX_DOWNLOAD_ATTEMPTS && queuedDownload.attempts < (queuedDownload.urls.numberOfUrls - 1) * MAX_DOWNLOAD_ATTEMPTS) {
//            url = queuedDownload.urls.URLs.get(queuedDownload.urls.URLs.size() - 2).url;
//        } else if (queuedDownload.attempts > MAX_DOWNLOAD_ATTEMPTS) {
//            url = queuedDownload.urls.URLs.get(0).url;
//        }


        int numberOfIndexes = queuedDownload.urls.numberOfUrls - 1;
        int urlIndex = Math.min(queuedDownload.attempts / MAX_DOWNLOAD_ATTEMPTS, numberOfIndexes);

        String url = queuedDownload.urls.URLs.get(numberOfIndexes - urlIndex).url;


        LOGGER.info("Downloading {} from {}", queuedDownload.file, url);

        boolean interrupted = false;

        try {
            downloadFile(url, sha1, queuedDownload);
        } catch (InterruptedException e) {
            CustomFileUtils.forceDelete(queuedDownload.file);
            interrupted = true;
        } catch (SocketTimeoutException e) {
            CustomFileUtils.forceDelete(queuedDownload.file);
            LOGGER.error("Timeout - " + queuedDownload.file);
            e.printStackTrace();
        } catch (Exception e) {
            CustomFileUtils.forceDelete(queuedDownload.file);
            e.printStackTrace();
        } finally {
            synchronized (downloadsInProgress) {
                downloadsInProgress.remove(sha1);
            }
            boolean failed = true;


            if (Files.exists(queuedDownload.file)) {
                String hash = CustomFileUtils.getHash(queuedDownload.file, "SHA-1").orElse(null);

                if (!Objects.equals(hash, sha1)) {
                    bytesDownloaded -= queuedDownload.file.toFile().length();
                    LOGGER.error("File size: {} File hash: {} Desired file hash: {}", queuedDownload.file.toFile().length(), hash, sha1);
                } else {
                    // Runs on success
                    failed = false;
                    downloaded++;
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
                    LOGGER.warn("Download failed, retrying: " + url);
                    queuedDownload.attempts++;
                    synchronized (queuedDownloads) {
                        queuedDownloads.put(sha1, queuedDownload);
                    }
                } else {
                    queuedDownload.failureCallback.run();
                    semaphore.release();
                }
            }


            downloadNext();
        }
    }

    private synchronized void downloadNext() {
        if (downloadsInProgress.size() < MAX_DOWNLOADS_IN_PROGRESS && !queuedDownloads.isEmpty()) {
            String sha1 = queuedDownloads.keySet().stream().findFirst().get();
            QueuedDownload queuedDownload = queuedDownloads.remove(sha1);

            if (queuedDownload == null) {
                return;
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> downloadTask(sha1, queuedDownload), DOWNLOAD_EXECUTOR);

            synchronized (downloadsInProgress) {
                downloadsInProgress.put(sha1, new DownloadData(future, queuedDownload.file));
            }
        }
    }

    private void downloadFile(String urlString, String sha1, QueuedDownload queuedDownload) throws IOException, InterruptedException {

        Path outFile = queuedDownload.file;

        if (Files.exists(outFile)) {
            if (Objects.equals(sha1, CustomFileUtils.getHash(outFile, "SHA-1").orElse(null))) {
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
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(5000);

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
        private final List<Url> URLs = new ArrayList<>();
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
