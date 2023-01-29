package pl.skidam.automodpack;

import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.MinecraftUserName;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

public class Download {
    private double bytesPerSecond;
    private long totalBytesRead;
    private boolean isDownloading;
    private long fileSize;
    private double downloadETA;

    public String download(String downloadUrl, File outFile) throws Exception {
        isDownloading = false;
        URL url = new URL(downloadUrl);
        URLConnection connection = url.openConnection();
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setRequestProperty("Minecraft-Username", MinecraftUserName.get());
        connection.setRequestProperty("User-Agent", "github/skidamek/automodpack/" + AutoModpack.VERSION);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(5000);
        fileSize = connection.getContentLengthLong();

        if (!outFile.getParentFile().exists()) {
            outFile.getParentFile().mkdirs();
        }

        if (outFile.exists()) {
            CustomFileUtils.forceDelete(outFile, false);
        }

        InputStream inputStream = connection.getInputStream();
        String encoding = connection.getHeaderField("Content-Encoding");
        if (encoding != null && encoding.equals("gzip")) {
            inputStream = new GZIPInputStream(inputStream);
        }
        OutputStream outputStream = new FileOutputStream(outFile);

        Instant start = Instant.now();
        totalBytesRead = 0;
        byte[] buffer = new byte[8192];
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
        }

        inputStream.close();
        outputStream.close();

        isDownloading = false;

        return CustomFileUtils.getSHA512(outFile); // return the sha512 checksum
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
