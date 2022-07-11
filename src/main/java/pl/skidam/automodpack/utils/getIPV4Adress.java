package pl.skidam.automodpack.utils;

import java.nio.charset.StandardCharsets;

public class getIPV4Adress {
    public static String getIPV4Address() {
        try (java.util.Scanner s = new java.util.Scanner(new java.net.URL("https://api.ipify.org").openStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
            return s.next();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}