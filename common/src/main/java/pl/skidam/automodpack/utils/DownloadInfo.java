package pl.skidam.automodpack.utils;

public class DownloadInfo {
    private final String fileName;
    private double bytesDownloaded = -1;
    private double downloadSpeed = -1;
    private boolean isDownloading = true;
    private boolean isCancelled = false;
    private long fileSize = -1;
    private double eta = -1;
    private double bytesPerSecond = -1;

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

    public void setDownloading(boolean isDownloading) {
        this.isDownloading = isDownloading;
    }

    public void setCancelled(boolean isCancelled) {
        this.isCancelled = isCancelled;
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

    public boolean isDownloading() {
        return isDownloading;
    }

    public boolean isCancelled() {
        return isCancelled;
    }

    public long getFileSize() {
        return fileSize;
    }

    public double getEta() {
        return eta;
    }
}
