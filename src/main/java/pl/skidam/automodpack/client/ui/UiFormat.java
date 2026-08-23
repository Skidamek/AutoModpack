package pl.skidam.automodpack.client.ui;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.utils.ByteFormat;

public final class UiFormat {
	private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

	private UiFormat() {}

	public static String formatSize(long bytes) {
		return ByteFormat.formatSize(bytes);
	}

	/** Translates {@code keyBase.singular} when the count is 1, otherwise {@code keyBase.plural}; the count is the first format argument. */
	public static MutableComponent plural(long count, String keyBase, Object... extraArgs) {
		String key = keyBase + (count == 1 ? ".singular" : ".plural");
		Object[] args = new Object[extraArgs.length + 1];
		args[0] = count;
		System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
		return VersionedText.translatable(key, args);
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
