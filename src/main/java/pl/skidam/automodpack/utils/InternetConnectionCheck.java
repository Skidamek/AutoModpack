package pl.skidam.automodpack.utils;

import java.net.HttpURLConnection;
import java.net.URL;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class InternetConnectionCheck {

    public static boolean InternetConnectionCheck(String url) {
        // Internet connection check
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(30000); // 30 seconds
            int responseCode = connection.getResponseCode();
            if (responseCode != 200 && responseCode != 404 && responseCode != 400) {
                throw new Exception("AutoModpack -- Internet isn't available, Failed to get code 200/404/400 from " + connection.getURL().toString());
            } else {
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Make sure that you have an internet connection!");
            new Wait(1000);
            return false;
        }
    }
}