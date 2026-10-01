package pl.skidam.automodpack_core.screen;

import java.util.Locale;

/**
 * The pack's voice on the download screen: its display name, one flavor line, and the accent tint of the progress bar as
 * an RGB int, where 0 means no accent and keeps the default green.
 */
public record PackIdentity(String name, String flavor, int accentColor) {
	public PackIdentity {
		name = name == null ? "" : name;
		flavor = flavor == null ? "" : flavor;
	}

	public static PackIdentity of(String name, String flavor, String accent) {
		return new PackIdentity(name, flavor, parseAccentColor(accent));
	}

	/** Whether the accent is blank or valid hex {@code RRGGBB} with an optional leading {@code #}; durable state may carry only these. */
	public static boolean isValidAccent(String accent) {
		String hex = hexDigits(accent);
		if (hex.isEmpty()) return true;
		if (hex.length() > 6) return false;
		try {
			return Long.parseLong(hex.toLowerCase(Locale.ROOT), 16) >= 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** Parses a hex accent into an RGB int; blank or malformed input reads as 0, the no-accent default. */
	public static int parseAccentColor(String accent) {
		String hex = hexDigits(accent);
		if (hex.isEmpty() || hex.length() > 6) return 0;
		try {
			long value = Long.parseLong(hex.toLowerCase(Locale.ROOT), 16);
			return value >= 0 && value <= 0xFFFFFF ? (int) value : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String hexDigits(String accent) {
		String hex = accent == null ? "" : accent.strip();
		return hex.startsWith("#") ? hex.substring(1) : hex;
	}
}
