package pl.skidam.automodpack.utils;

import java.net.HttpURLConnection;
import java.net.URL;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class InternetConnectionCheck {

    public static boolean InternetConnectionCheck(String url) {
        // Internet connection check
        int responseCode = 0;
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("X-Minecraft-Username", "other-packet");
            connection.setConnectTimeout(3000); // 3 seconds
            connection.setReadTimeout(3000); // 3 seconds as well
            responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                LOGGER.error("AutoModpack -- Internet isn't available, Failed to get code 200 from " + connection.getURL().toString());
                connection.disconnect();
                return false;
            } else {
                connection.disconnect();
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Something went wrong (code: {}) {}", responseCode, e);
            return false;
        }
    }
}
