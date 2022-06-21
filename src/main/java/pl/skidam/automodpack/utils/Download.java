package pl.skidam.automodpack.utils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class Download {

    public static int downloadPercent = 0;

    public static boolean Download(String link, File output) {
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
            String percent = "0";

            while ((read = in.read(buffer, 0, 1024)) >= 0) {
                bout.write(buffer, 0, read);
                downloaded += read;
                percentDownloaded = (downloaded * 100) / fileSize;

                // if lastPercent != percent
                if (!Objects.equals(lastPercent, percent)) {
                    percent = (String.format("%.0f", percentDownloaded));
                    downloadPercent = Integer.parseInt(percent);
                    if (percent.contains("0")) {
                        LOGGER.info(percent + "%");
                    }
                    lastPercent = percent;

                // if lastPercent == percent
                } else {
                    percent = (String.format("%.0f", percentDownloaded));
                }
            }
            bout.close();
            in.close();
            return false;

        } catch (IOException ex) {
            //new Error();
            ex.printStackTrace();
            return true;
        }
    }
}
