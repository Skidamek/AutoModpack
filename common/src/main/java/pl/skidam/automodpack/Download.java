package pl.skidam.automodpack;

import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.MinecraftUserName;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

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
        connection.setRequestProperty("Minecraft-Username", MinecraftUserName.get());
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
        OutputStream outputStream = new FileOutputStream(outFile);
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        DigestInputStream digestInputStream = new DigestInputStream(inputStream, md);

        Instant start = Instant.now();
        totalBytesRead = 0;
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = digestInputStream.read(buffer)) != -1) {
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

        // calculate sha512 checksum
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        String sha512 = sb.toString();

        inputStream.close();
        outputStream.close();
        digestInputStream.close();

        isDownloading = false;

        return sha512; // return the sha512 checksum
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
