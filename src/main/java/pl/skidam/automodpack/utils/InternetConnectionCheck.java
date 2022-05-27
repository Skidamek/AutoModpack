package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpackClient;

import java.net.HttpURLConnection;
import java.net.URL;

public class InternetConnectionCheck {

    public static boolean InternetConnectionCheck() {
        Thread.currentThread().setPriority(10);
        // Internet connection check
        while (true) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("https://www.google.com").openConnection();
                connection.setRequestMethod("HEAD");
                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    throw new Exception("AutoModpack -- Internet isn't available, Failed to get code 200 from " + connection.getURL().toString());
                } else {
                    break;
                }
            } catch (Exception e) {
                AutoModpackClient.LOGGER.warn("Make sure that you have an internet connection!");
            }
            Wait.wait(1000);
        }
        return true;
    }
}
