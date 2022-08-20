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
                    if (!"level".equals(string) && !url.contains("..")) {
                        if (url.endsWith("/modpack") || url.endsWith("/modpack.zip")) {
                            return true;
                        } else if (url.startsWith("https://drive.google.com/")) {
                            return true;
                        } else if (url.startsWith("https://www.dropbox.com/s/") && url.endsWith("/modpack.zip?dl=0") || url.endsWith("/modpack.zip?dl=1")) {
                            return true;
                        } else if (url.startsWith("https://www.mediafire.com/file/") && url.endsWith("/modpack.zip/file")) {
                            return true;
                        }
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            } catch (URISyntaxException e) {
                e.printStackTrace();
                return false;
            }
        } else {
            return false;
        }
        return false;
    }
}