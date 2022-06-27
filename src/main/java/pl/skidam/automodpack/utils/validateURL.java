package pl.skidam.automodpack.utils;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

public class validateURL {
    public static boolean validateURL(String url) {
        String localIp = "0.0.0.0";
        try {
            localIp = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) { // ignore
        }
        if (!url.isEmpty() && !url.equals(localIp) && !url.equals("0.0.0.0") && !url.equals("localhost")) {
            try {
                URI URI = new URI(url);
                String string = URI.getScheme();
                if ("http".equals(string) || "https".equals(string) || "level".equals(string)) {
                    if (!"level".equals(string) || !url.contains("..") && url.endsWith("/modpack")) {
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            } catch (URISyntaxException e) {
                return false;
            }
        } else {
            return false;
        }
    }

}
