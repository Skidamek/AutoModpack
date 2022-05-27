package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpackClient;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;

public class Download {
    public static boolean Download(String link, File output) {
        Thread.currentThread().setPriority(10);
        try {
            URL url = new URL(link);
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            double fileSize = (double) http.getContentLengthLong();
            BufferedInputStream in = new BufferedInputStream(http.getInputStream());
            FileOutputStream fos = new FileOutputStream(output);
            BufferedOutputStream bout = new BufferedOutputStream(fos, 1024);
            byte[] buffer = new byte[1024];
            double downloaded = 0.00;
            int read;
            double percentDownloaded;
            String lastPercent = null;
            String percent = null;

            while ((read = in.read(buffer, 0, 1024)) >= 0) {
                bout.write(buffer, 0, read);
                downloaded += read;
                percentDownloaded = (downloaded * 100) / fileSize;

                // if lastPercent != percent
                if (!Objects.equals(lastPercent, percent)) {
                    percent = (String.format("%.0f", percentDownloaded));
                    AutoModpackClient.LOGGER.info(percent + "%");
                    lastPercent = percent;

                    // if lastPercent == percent
                } else {
                    percent = (String.format("%.0f", percentDownloaded));
                }
            }
            bout.close();
            in.close();
            return true;

        } catch (IOException ex) {
            AutoModpackClient.LOGGER.error("Failed to update/download!");
            new ToastExecutor(5);
            ex.printStackTrace();
            return false;
        }
    }
}
