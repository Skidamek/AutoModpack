package pl.skidam.automodpack_core.utils;

import java.util.Locale;

public class PlatformUtils {

    private static Boolean macCache;
    private static Boolean androidCache;

    public static boolean isMac() {
        if (macCache != null) return macCache;

        String runtime = System.getProperty("os.name");
        if (runtime != null && runtime.toLowerCase(Locale.ENGLISH).contains("mac")) {
            return macCache = true;
        }

        return macCache = false;
    }

    public static boolean isAndroid() {
        if (androidCache != null) return androidCache;

        String runtime = System.getProperty("java.runtime.name");
        if (runtime != null && runtime.toLowerCase(Locale.ENGLISH).contains("android")) {
            return androidCache = true;
        }

        try {
            Class.forName("android.os.Build");
            return androidCache = true;
        } catch (ClassNotFoundException ignored) { }

        return androidCache = false;
    }
}
