package pl.skidam.automodpack.utils;

import java.net.HttpURLConnection;
import java.net.URL;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class WebFileSize {
    public static Long webfileSize(String link) {
        long size = 0;
        try {
            URL url = new URL(link);
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            http.setRequestMethod("GET");
            http.setConnectTimeout(5000); // 5 seconds
            size = Long.parseLong(http.getHeaderField("Content-Length"));
        } catch (Exception e) {
            LOGGER.error("Make sure that you have an internet connection! " + e);
            new Error();
        }

        return size;  // returns the size of the file in bytes
    }
}
