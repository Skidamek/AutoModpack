package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.client.ui.AutoModpackToast;

import java.net.HttpURLConnection;
import java.net.URL;

public class InternetConnection {
    public static boolean Check(String url, String reasonIfConnectionFailed) {
        // Internet connection check
        int responseCode = 0;
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("Minecraft-Username", "other-packet");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                AutoModpack.LOGGER.error("Failed to get code 200 (got {}) from {}", responseCode, connection.getURL().toString());
                connection.disconnect();
                return false;
            } else {
                connection.disconnect();
                return true;
            }
        } catch (Exception e) {
            AutoModpackToast.add(10);
            if (reasonIfConnectionFailed.equals("unknown")) {
                AutoModpack.LOGGER.error("Something went wrong (code: {})", responseCode);
            } else {
                AutoModpack.LOGGER.error("{} (code: {})", reasonIfConnectionFailed, responseCode);
            }
            e.printStackTrace();
            return false;
        }
    }
    public static boolean Check(String url) {
        return Check(url, "unknown");
    }
}
