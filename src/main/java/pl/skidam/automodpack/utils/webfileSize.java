package pl.skidam.automodpack.utils;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

public class webfileSize {
    // GITHUB COPILOT, I LOVE YOU!!!
    public static String webfileSize(String link) {
        String size = "";
        try {
            URL url = new URL(link);
            URLConnection conn = url.openConnection();
            size = conn.getHeaderField("Content-Length");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return size;  // returns the size of the file in bytes
    }
}
