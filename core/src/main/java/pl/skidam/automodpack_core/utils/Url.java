package pl.skidam.automodpack_core.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Url {

    public static String removeHttpPrefix(String inputUrl) {
        String regex = "^(https?://)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(inputUrl);

        if (matcher.find()) {
            return inputUrl.substring(matcher.end());
        } else {
            return inputUrl; // No match, return the original URL
        }
    }
}