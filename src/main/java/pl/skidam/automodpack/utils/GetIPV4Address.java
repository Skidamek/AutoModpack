package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpackMain;

import java.nio.charset.StandardCharsets;

public class GetIPV4Address {
    public static String getIPV4Address() {
        try (java.util.Scanner s = new java.util.Scanner(new java.net.URL("https://api.ipify.org").openStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
            return s.next();
        } catch (Exception e) {
            AutoModpackMain.LOGGER.error("Failed to get IPV4 address!\n" + e);
            return "";
        }
    }
}
