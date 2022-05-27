package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpackClient;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

public class WebFileSize {
    // GITHUB COPILOT, I LOVE YOU!!!
    public static String webfileSize(String link) {
        Thread.currentThread().setPriority(10);
        String size = "";
        try {
            URL url = new URL(link);
            URLConnection conn = url.openConnection();
            size = conn.getHeaderField("Content-Length");
        } catch (IOException e) {
            e.printStackTrace();
            AutoModpackClient.LOGGER.error("Make sure that you have an internet connection!");
            new Error();
        }
        return size;  // returns the size of the file in bytes
    }
}
