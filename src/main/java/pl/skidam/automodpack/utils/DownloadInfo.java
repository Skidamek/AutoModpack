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

public class DownloadInfo {
    private final String fileName;
    private double bytesDownloaded;
    private double downloadSpeed;
    private long fileSize;
    private double eta;
    private double bytesPerSecond;

    public DownloadInfo(String fileName) {
        this.fileName = fileName;
    }
    public void setBytesPerSecond(double bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
    }
    public void setBytesDownloaded(long bytesDownloaded) {
        this.bytesDownloaded = bytesDownloaded;
    }

    public void setDownloadSpeed(double downloadSpeed) {
        this.downloadSpeed = downloadSpeed;
    }

    public long setFileSize(long fileSize) {
        return this.fileSize = fileSize;
    }

    public double setEta(double eta) {
        return this.eta = eta;
    }
    public double getBytesPerSecond() {
        return bytesPerSecond;
    }
    public String getFileName() {
        return fileName;
    }

    public double getBytesDownloaded() {
        return bytesDownloaded;
    }

    public double getDownloadSpeed() {
        return downloadSpeed;
    }

    public long getFileSize() {
        return fileSize;
    }

    public double getEta() {
        return eta;
    }
}
