package pl.skidam.automodpack;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class utils {

    public void checkInternetAccess() throws Exception {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("https://www.google.com").openConnection();
            connection.setRequestMethod("HEAD");
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("Failed to get code 200 from " + connection.getURL().toString());
            }
        } catch (Exception e) {
            System.err.println("Make sure that you have an internet connection!");
            throw e;
        }
    }
}
