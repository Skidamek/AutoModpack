package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpack;

import java.net.HttpURLConnection;
import java.net.URL;

public class WebFileSize {
    /**
     * Returns the size of the file at the given URL.
     * @return size
     */
    public static Long getWebFileSize(String link) {
        long size = 0;
        try {
            URL url = new URL(link);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            size = connection.getContentLengthLong();
            connection.disconnect();
        } catch (Exception e) {
            AutoModpack.LOGGER.error("Make sure that you have an internet connection! " + e);
        }

        return size;  // returns the size of the file in bytes
    }
}