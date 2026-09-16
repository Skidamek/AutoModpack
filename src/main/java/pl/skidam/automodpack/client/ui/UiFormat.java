package pl.skidam.automodpack.client.ui;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import pl.skidam.automodpack_core.update.UpdatePlan;

public final class UiFormat {
	private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

	private UiFormat() {}

	public static String formatSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024L * 1024L) return (bytes / 1024) + " KiB";
		if (bytes < 1024L * 1024L * 1024L) return (bytes / (1024L * 1024L)) + " MiB";
		return (bytes / (1024L * 1024L * 1024L)) + " GiB";
	}

	public static String formatInstant(Instant instant) {
		return HISTORY_TIME.format(instant);
	}

	static String filePath(UpdatePlan.FileKey file) {
		return rootLabel(file.root()) + "/" + file.relativePath();
	}

	static String changePath(UpdatePlan.FileKey file) {
		return file.relativePath();
	}

	static String rootLabel(UpdatePlan.Root root) {
		return switch (root) {
			case PROJECTION -> "active";
			case OVERLAY -> "editable";
			case GAME_DIR -> "game";
		};
	}
}
