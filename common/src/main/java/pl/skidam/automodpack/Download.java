package pl.skidam.automodpack;

import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.MinecraftUserName;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

import static pl.skidam.automodpack.StaticVariables.*;

public class Download {
    private double bytesPerSecond;
    private long totalBytesRead;
    private boolean isDownloading;
    private long fileSize;
    private double downloadETA;
    private static final int BUFFER_SIZE = 16 * 1024;

    public void download(String downloadUrl, File outFile) {
        try {
            isDownloading = false;
            URL url = new URL(downloadUrl);
            URLConnection connection = url.openConnection();
            connection.addRequestProperty("Accept-Encoding", "gzip");
            connection.addRequestProperty("Minecraft-Username", MinecraftUserName.get());
            connection.addRequestProperty("User-Agent", "github/skidamek/automodpack/" + VERSION);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(5000);
            fileSize = connection.getContentLengthLong();


            if (!outFile.getParentFile().exists()) {
                outFile.getParentFile().mkdirs();
            }

            boolean fileAlreadyExists = outFile.exists();

            if (outFile.exists()) {
                CustomFileUtils.forceDelete(outFile, false);
                outFile = new File(outFile + ".tmp");
            }

            OutputStream outputStream = new FileOutputStream(outFile);
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
            }

            inputStream.close();
            outputStream.close();

            isDownloading = false;

            if (fileAlreadyExists && outFile.toString().endsWith(".tmp")) {
                File finalFile = new File(outFile.toString().replace(".tmp", ""));
                CustomFileUtils.copyFile(outFile, finalFile);
                CustomFileUtils.forceDelete(outFile, false);
            }
        } catch (IOException e) {
            if (outFile.exists()) {
                CustomFileUtils.forceDelete(outFile, false);
            }
            e.printStackTrace();
        }
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
