package pl.skidam.automodpack.utils;

import java.net.URI;
import java.net.URISyntaxException;

public class ValidateURL {
    public static boolean ValidateURL(String url) {
        if (!url.isEmpty() && !url.equals("0.0.0.0") && !url.equals("localhost")) {
            try {
                URI URI = new URI(url);
                String string = URI.getScheme();
                if ("http".equals(string) || "https".equals(string) || "level".equals(string)) {
                    if (!"level".equals(string) || !url.contains("..")) {
                        if (url.endsWith("/modpack") || url.endsWith("/modpack.zip")) {
                            return true;
                        } else {
                            return false;
                        }
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