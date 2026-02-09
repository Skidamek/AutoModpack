package pl.skidam.automodpack_loader_core.utils;

import java.util.Locale;

public class SpeedFormatter {

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 0) return "-1";
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        
        double kb = bytesPerSec / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB/s", kb);
        }
        
        double mb = kb / 1024.0;
        return String.format(Locale.US, "%.1f MB/s", mb);
    }

    public static String formatETA(long seconds) {
        if (seconds < 0) return "-1";

        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (days > 0) {
            return String.format("%dd %dh", days, hours);
        } else if (hours > 0) {
            // e.g. 1h 05m
            return String.format("%dh %02dm", hours, minutes);
        } else if (minutes > 0) {
            // e.g. 05m 12s
            return String.format("%02dm %02ds", minutes, secs);
        } else {
            // e.g. 45s
            return String.format("%ds", secs);
        }
    }
}