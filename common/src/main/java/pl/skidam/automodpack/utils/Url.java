package pl.skidam.automodpack.utils;

public class Url {
    // This class make sure to prevent issues like this
    // https://github.com/Skidamek/AutoModpack/issues/70

    // TODO change it to base64 encoding

    private static final String[][] ENCODING_TABLE = {
            { " ", "%20" },
            { "[", "%5B" },
            { "]", "%5D" },
            { "{", "%7B" },
            { "}", "%7D" },
            { "^", "%5E" },
            { "`", "%60" },
            { "'", "%27" },
            { "&", "%26" },
            { "$", "%24" },
            { "~", "%7E" },
            { "‐", "%E2%80%90" }
    };

    public static String encode(String url) {
        for (String[] pair : ENCODING_TABLE) {
            url = url.replace(pair[0], pair[1]);
        }
        return url;
    }

    public static String decode(String url) {
        for (String[] pair : ENCODING_TABLE) {
            url = url.replace(pair[1], pair[0]);
        }
        return url;
    }
}