package pl.skidam.automodpack;

import org.apache.commons.codec.binary.Hex;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.MinecraftUserName;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
            CustomFileUtils.forceDelete(outFile);
        }

        InputStream inputStream = connection.getInputStream();
        OutputStream outputStream = new FileOutputStream(outFile);
        // Create a new instance of the SHA-512 digest
        DigestInputStream digestInputStream = new DigestInputStream(inputStream, MessageDigest.getInstance("SHA-512"));

        Instant start = Instant.now();
        totalBytesRead = 0;
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = digestInputStream.read(buffer)) != -1) {
            isDownloading = true;
            outputStream.write(buffer, 0, bytesRead);
            totalBytesRead += bytesRead;
            Instant now = Instant.now();
            Duration elapsed = Duration.between(start, now);
            double seconds = (double) elapsed.toMillis() / 1000;
            bytesPerSecond = totalBytesRead / seconds;
            downloadETA = -1;
            if (bytesPerSecond > 0) downloadETA = (fileSize - totalBytesRead) / bytesPerSecond;
            if (downloadETA > 0) downloadETA = Math.ceil(downloadETA);
        }
        // get the SHA-512 checksum of the file
        String sha512 = Hex.encodeHexString(digestInputStream.getMessageDigest().digest());

        inputStream.close();
        outputStream.close();
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
