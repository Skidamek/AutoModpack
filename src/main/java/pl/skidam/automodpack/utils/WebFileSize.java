package pl.skidam.automodpack.utils;

import java.net.HttpURLConnection;
import java.net.URL;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class WebFileSize {
    public static Long getWebFileSize(String link) {
        long size = 0;
        try {
            URL url = new URL(link);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("X-Minecraft-Username", "other-packet");
            connection.setConnectTimeout(5000); // 5 seconds
            connection.setConnectTimeout(5000); // 5 seconds as well
            size = Long.parseLong(connection.getHeaderField("Content-Length"));
            connection.disconnect();
        } catch (Exception e) {
            LOGGER.error("Make sure that you have an internet connection! " + e);
            new Error();
        }

        return size;  // returns the size of the file in bytes
    }
}