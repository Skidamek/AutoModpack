/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import pl.skidam.automodpack.client.ScreenTools;

import java.io.*;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import static pl.skidam.automodpack.GlobalVariables.LOGGER;
import static pl.skidam.automodpack.GlobalVariables.AM_VERSION;

public class DownloadManager {
    private static final int MAX_DOWNLOADS_IN_PROGRESS = 5;
    private static final int BUFFER_SIZE = 16 * 1024;
    private final ExecutorService DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(MAX_DOWNLOADS_IN_PROGRESS, new ThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build());
    private final Map<String, QueuedDownload> queuedDownloads = new ConcurrentHashMap<>();
    public final Map<String, DownloadData> downloadsInProgress = new ConcurrentHashMap<>();
    private final Map<String, Long> fileSizes = new ConcurrentHashMap<>();
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private long bytesDownloaded = 0;
    private long bytesToDownload = 0;
    private int addedToQueue = 0;
    private int downloaded = 0;
    private final Semaphore semaphore = new Semaphore(0);

    public DownloadManager() {
        if (!ScreenTools.getScreenString().contains("downloadscreen")) {
            ScreenTools.setTo.download();
        }
    }

    public DownloadManager(long bytesToDownload) {
        this.bytesToDownload = bytesToDownload;
        if (!ScreenTools.getScreenString().contains("downloadscreen")) {
            ScreenTools.setTo.download();
        }
    }

    public void download(Path file, String sha1, String url, Runnable successCallback, Runnable failureCallback) {
        if (!queuedDownloads.containsKey(url)) {
            retryCounts.put(url, 1);
            queuedDownloads.put(url, new QueuedDownload(file, sha1, successCallback, failureCallback));
            downloadNext();
            addedToQueue++;
        }
    }

    private void downloadNext() {
        if (downloadsInProgress.size() < MAX_DOWNLOADS_IN_PROGRESS && !queuedDownloads.isEmpty()) {
            String url = queuedDownloads.keySet().iterator().next();
            QueuedDownload queuedDownload = queuedDownloads.remove(url);

            Runnable downloadTask = () -> {
                try {
                    downloadFile(url, queuedDownload);

                    if (!Objects.equals(CustomFileUtils.getHash(queuedDownload.file, "SHA-1"), queuedDownload.sha1)) {

                        bytesDownloaded -= queuedDownload.file.toFile().length();

                        // Runs only when failure
                        if (retryCounts.get(url) <= 3) {
                            retryCounts.put(url, retryCounts.get(url) + 1); // Increment the retry count here
                            LOGGER.warn("Download failed, retrying: " + url);
                            queuedDownloads.put(url, queuedDownload);
                        } else {
                            LOGGER.error("Download failed after {} retries: {}", retryCounts.get(url), url);
                            queuedDownload.failureCallback.run();
                        }

                        CustomFileUtils.forceDelete(queuedDownload.file);

                    // Runs only when success
                    } else if (Files.exists(queuedDownload.file)) {

                        downloaded++;
                        queuedDownload.successCallback.run();
                    }
                } catch (SocketTimeoutException | InterruptedException ignored) {

                } catch (Exception e) {
                    queuedDownload.failureCallback.run();
                    e.printStackTrace();
                } finally {
                    // Runs every time
                    fileSizes.remove(url);
                    downloadsInProgress.remove(url);
                    retryCounts.remove(url);
                    semaphore.release();

                    downloadNext();
                }
            };

            CompletableFuture<Void> future = CompletableFuture.runAsync(downloadTask, DOWNLOAD_EXECUTOR);

            synchronized (downloadsInProgress) {
                downloadsInProgress.put(url, new DownloadData(future, queuedDownload.file));
            }
        }
    }

    private void downloadFile(String urlString, QueuedDownload queuedDownload) throws IOException, NoSuchAlgorithmException, InterruptedException {

        Path outFile = queuedDownload.file;

        if (Files.exists(outFile)) {
            if (Objects.equals(CustomFileUtils.getHash(outFile, "SHA-1"), queuedDownload.sha1)) {
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
        connection.setRequestProperty("Content-Type", "application/octet-stream; charset=UTF-8");
        connection.addRequestProperty("Accept-Encoding", "gzip");
        connection.addRequestProperty("Minecraft-Username", MinecraftUserName.get());
        connection.addRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(5000);

        fileSizes.put(url.toString(), connection.getContentLengthLong());

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


    public String getTotalDownloadSpeedInReadableFormat() {
        long totalDownloadSpeed = getTotalDownloadSpeed();

        if (totalDownloadSpeed == 0) {
            return "-1";
        }

        // Use the formatSize() method to format the total download speed into a human-readable format
        return addUnitsPerSecond(totalDownloadSpeed);
    }

    public String getTotalDownloadSpeedInReadableFormatFast(long totalDownloadSpeed) {
        if (totalDownloadSpeed == 0) {
            return "-1";
        }

        // Use the formatSize() method to format the total download speed into a human-readable format
        return addUnitsPerSecond(totalDownloadSpeed);
    }


    public String getTotalETA() {
        long totalBytesRemaining = fileSizes.values().stream().mapToLong(Long::longValue).sum()
                - downloadsInProgress.values().stream().mapToLong(data -> data.file.toFile().length()).sum();

        long averageSpeed = getTotalDownloadSpeed();

        return averageSpeed == 0 ? "0" : totalBytesRemaining / averageSpeed + "s";
    }

    public String getTotalETAFast(long totalDownloadSpeed) {
        long totalBytesRemaining = bytesToDownload - bytesDownloaded;

        return totalDownloadSpeed == 0 ? "0" : totalBytesRemaining / totalDownloadSpeed + "s";
    }

    public String getETAOfFile(String url) {
        if (!fileSizes.containsKey(url) || !isDownloadInProgress(url)) {
            return "-1";
        }

        long fileSize = fileSizes.get(url);
        long bytesDownloaded = getFileDownloadedSize(url);
        long bytesRemaining = fileSize - bytesDownloaded;

        long averageSpeed = getTotalDownloadSpeed();

        return averageSpeed == 0 ? "0" : bytesRemaining / averageSpeed + "s";
    }

    public String calculateAndFormatDownloadedFileSize(String url) {
        if (!fileSizes.containsKey(url) || !isDownloadInProgress(url)) {
            return null;
        }

        long fileSize = fileSizes.get(url);
        long downloadedSize = getFileDownloadedSize(url);

        return formatSize(fileSize, downloadedSize);
    }

    public String formatSize(long fileSize, long downloadedSize) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;

        while (fileSize > 1024 && unitIndex < units.length) {
            fileSize /= 1024;
            unitIndex++;
        }

        downloadedSize /= Math.pow(1024, unitIndex);

        return downloadedSize + "/" + fileSize + units[unitIndex];
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


    public long getFileDownloadedSize(String url) {
        if (isDownloadInProgress(url)) {
            return downloadsInProgress.get(url).file.toFile().length();
        } else {
            return -1;
        }
    }

    public float getTotalPercentageOfFileSizeDownloaded() {
        return (float) bytesDownloaded / bytesToDownload * 100;
    }

    public float getPercentageOfFileSizeDownloaded(String url) {
        if (!fileSizes.containsKey(url) || !isDownloadInProgress(url)) {
            return -1;
        }

        long fileSize = fileSizes.get(url);
        long downloadedSize = getFileDownloadedSize(url);

        return (float) downloadedSize / fileSize * 100;
    }


    public String getStage() {
        // files downloaded / files downloaded + queued
        return downloaded + "/" + addedToQueue;
    }

    public boolean isDownloadInProgress(String url) {
        return downloadsInProgress.containsKey(url);
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
        downloadsInProgress.clear();
        fileSizes.clear();
        retryCounts.clear();
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

    private static class QueuedDownload {
        public final Path file;
        public final String sha1;
        Runnable successCallback;
        Runnable failureCallback;

        public QueuedDownload(Path file, String sha1, Runnable successCallback, Runnable failureCallback) {
            this.file = file;
            this.sha1 = sha1;
            this.successCallback = successCallback;
            this.failureCallback = failureCallback;
        }
    }

    public static class DownloadData {
        final CompletableFuture<Void> future;
        final Path file;
        final Instant startTime = Instant.now();

        DownloadData(CompletableFuture<Void> future, Path file) {
            this.future = future;
            this.file = file;
        }

        public String getFileName() {
            return file.getFileName().toString();
        }
    }
}
