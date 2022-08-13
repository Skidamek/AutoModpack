package pl.skidam.automodpack.utils;

import java.net.HttpURLConnection;
import java.net.URL;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class InternetConnectionCheck {

    public static boolean InternetConnectionCheck(String url) {
        // Internet connection check
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000); // 10 seconds
            connection.setReadTimeout(10000); // 10 seconds as well
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                LOGGER.error("AutoModpack -- Internet isn't available, Failed to get code 200 from " + connection.getURL().toString());
                connection.disconnect();
                return false;
            } else {
                connection.disconnect();
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Something went wrong " + e);
            return false;
        }
    }
}
