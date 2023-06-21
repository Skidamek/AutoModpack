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

package pl.skidam.automodpack;

import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.DownloadInfo;
import pl.skidam.automodpack.utils.MinecraftUserName;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

import static pl.skidam.automodpack.StaticVariables.VERSION;

public class Download {
    private double bytesPerSecond;
    private long totalBytesRead;
    private boolean isDownloading;
    private long fileSize;
    private double downloadETA;
    private static final int BUFFER_SIZE = 16 * 1024;

    public void download(String downloadUrl, Path outFile, DownloadInfo downloadInfo) {
        try {
            isDownloading = false;
            URL url = new URL(downloadUrl);
            URLConnection connection = url.openConnection();
            connection.setRequestProperty("Content-Type", "application/octet-stream; charset=UTF-8");
            connection.addRequestProperty("Accept-Encoding", "gzip");
            connection.addRequestProperty("Minecraft-Username", MinecraftUserName.get());
            connection.addRequestProperty("User-Agent", "github/skidamek/automodpack/" + VERSION);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(5000);
            fileSize = connection.getContentLengthLong();


            if (!Files.exists(outFile.getParent())) {
                Files.createDirectories(outFile.getParent());
            }

            boolean fileAlreadyExists = Files.exists(outFile);

            if (fileAlreadyExists) {
                CustomFileUtils.forceDelete(outFile, false);
                outFile = Paths.get(outFile + ".tmp");
            }

            try (OutputStream outputStream = Files.newOutputStream(outFile, StandardOpenOption.CREATE)) {
                InputStream inputStream = connection.getInputStream();
                String encoding = connection.getHeaderField("Content-Encoding");
                if (encoding != null && encoding.equals("gzip")) {
                    inputStream = new GZIPInputStream(inputStream, BUFFER_SIZE);
                }

                Instant start = Instant.now();
                totalBytesRead = 0;
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    isDownloading = true;
                    totalBytesRead += bytesRead;
                    Instant now = Instant.now();
                    Duration elapsed = Duration.between(start, now);
                    double seconds = (double) elapsed.toMillis() / 1000;
                    bytesPerSecond = totalBytesRead / seconds;
                    downloadETA = -1;
                    if (bytesPerSecond > 0) downloadETA = (fileSize - totalBytesRead) / bytesPerSecond;
                    if (downloadETA > 0) downloadETA = Math.ceil(downloadETA);

                    if (downloadInfo != null) {
                        downloadInfo.setBytesDownloaded(totalBytesRead);
                        downloadInfo.setDownloadSpeed(bytesPerSecond / 1024 / 1024);
                        downloadInfo.setEta(downloadETA);
                        downloadInfo.setFileSize(fileSize);
                        downloadInfo.setBytesPerSecond(bytesPerSecond);

                        ModpackUpdater.totalBytesDownloaded += bytesRead;
                    }
                }

                inputStream.close();
            }

            isDownloading = false;

            if (fileAlreadyExists) {
                Path finalFile = Paths.get(outFile.toString().replace(".tmp", ""));
                CustomFileUtils.copyFile(outFile, finalFile);
                CustomFileUtils.forceDelete(outFile, false);
            }
        } catch (IOException e) {
            if (Files.exists(outFile)) {
                CustomFileUtils.forceDelete(outFile, false);
            }
            e.printStackTrace();
        }
    }

    public void download(String downloadUrl, Path outFile) {
        download(downloadUrl, outFile, null);
    }

    public double getBytesPerSecond() {
        return bytesPerSecond;
    }
    public long getTotalBytesRead() {
        return totalBytesRead;
    }

    public double getETA() {
        return downloadETA;
    }

    public long getFileSize() {
        return fileSize;
    }

    public boolean isDownloading(){
        return this.isDownloading;
    }
}
