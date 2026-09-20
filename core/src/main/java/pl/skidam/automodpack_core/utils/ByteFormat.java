package pl.skidam.automodpack_core.utils;

import java.util.Locale;

/** Single 1024-ladder formatter for byte sizes, transfer speeds, and download ETAs. */
public final class ByteFormat {
	private ByteFormat() {}

	public static String formatSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		double kib = bytes / 1024.0;
		if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);
		if (kib < 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", kib / 1024.0);
		return String.format(Locale.ROOT, "%.1f GiB", kib / (1024.0 * 1024.0));
	}

	public static String formatSpeed(long bytesPerSec) {
		if (bytesPerSec < 0) return "-1";
		if (bytesPerSec < 1024) return bytesPerSec + " B/s";
		double kib = bytesPerSec / 1024.0;
		if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB/s", kib);
		return String.format(Locale.ROOT, "%.1f MiB/s", kib / 1024.0);
	}

	public static String formatETA(long seconds) {
		if (seconds < 0) return "-1";
		long days = seconds / 86400;
		long hours = (seconds % 86400) / 3600;
		long minutes = (seconds % 3600) / 60;
		long secs = seconds % 60;
		if (days > 0) return String.format(Locale.ROOT, "%dd %dh", days, hours);
		if (hours > 0) return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
		if (minutes > 0) return String.format(Locale.ROOT, "%02dm %02ds", minutes, secs);
		return String.format(Locale.ROOT, "%ds", secs);
	}
}
