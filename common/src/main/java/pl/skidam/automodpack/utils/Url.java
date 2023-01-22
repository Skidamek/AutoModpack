package pl.skidam.automodpack.utils;

public class Url {
    // This class make sure to prevent issues like this https://github.com/Skidamek/AutoModpack/issues/70

    public static String encode(String url) {
        url = url.replace(" ", "%20");
        url = url.replace("[", "%5B");
        url = url.replace("]", "%5D");
        url = url.replace("{", "%7B");
        url = url.replace("}", "%7D");
        url = url.replace("^", "%5E");
        url = url.replace("`", "%60");
        url = url.replace("'", "%27");
        url = url.replace("&", "%26");
        url = url.replace("$", "%24");
        url = url.replace("~", "%7E");
        return url;
    }

    public static String decode(String url) {
        url = url.replace("%20", " ");
        url = url.replace("%5B", "[");
        url = url.replace("%5D", "]");
        url = url.replace("%7B", "{");
        url = url.replace("%7D", "}");
        url = url.replace("%5E", "^");
        url = url.replace("%60", "`");
        url = url.replace("%27", "'");
        url = url.replace("%26", "&");
        url = url.replace("%24", "$");
        url = url.replace("%7E", "~");
        return url;
    }
}
