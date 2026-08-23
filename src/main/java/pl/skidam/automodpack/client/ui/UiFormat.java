package pl.skidam.automodpack.client.ui;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import net.minecraft.client.Minecraft;
/*? if >=26.2 {*/
import net.minecraft.locale.Language;
/*?} else {*/
/*import net.minecraft.client.resources.language.I18n;
*//*?}*/
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.PluralCategory;

public final class UiFormat {
	private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

	private UiFormat() {}

	public static String formatSize(long bytes) {
		return ByteFormat.formatSize(bytes);
	}

	/** Translates the CLDR plural category of the count, {@code keyBase.one/few/many/other}, falling back to {@code keyBase.other}; the count is the first format argument. */
	public static MutableComponent plural(long count, String keyBase, Object... extraArgs) {
		String key = keyBase + "." + PluralCategory.select(activeLanguage(), count).keySuffix();
		if (!keyExists(key)) key = keyBase + ".other";
		Object[] args = new Object[extraArgs.length + 1];
		args[0] = count;
		System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
		return VersionedText.translatable(key, args);
	}

	/** I18n lost its exists check in 26.2, where Language carries the same probe. */
	private static boolean keyExists(String key) {
		/*? if >=26.2 {*/
		return Language.getInstance().has(key);
		/*?} else {*/
		/*return I18n.exists(key);
		*//*?}*/
	}

	private static String activeLanguage() {
		return Minecraft.getInstance().options.languageCode;
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
