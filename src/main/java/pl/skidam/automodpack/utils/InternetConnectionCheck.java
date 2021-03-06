package pl.skidam.automodpack.utils;

import java.net.HttpURLConnection;
import java.net.URL;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class InternetConnectionCheck {

    public static boolean InternetConnectionCheck() {
        // Internet connection check
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("https://www.google.com").openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(30000); // 30 seconds
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("AutoModpack -- Internet isn't available, Failed to get code 200 from " + connection.getURL().toString());
            } else {
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Make sure that you have an internet connection!");
            LOGGER.error("Make sure that you have an internet connection!");
            LOGGER.error("Make sure that you have an internet connection!");
            new Wait(1000);
            return false;
        }
    }
}
