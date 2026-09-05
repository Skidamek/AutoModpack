package pl.skidam.automodpack_core.utils;

import java.util.Locale;

/**
 * CLDR cardinal plural categories for the languages AutoModpack ships. Rule receipts: unicode.org/reports/tr35 language plural rules.
 * A locale added to the lang directory without a case here silently falls back to the English one/other pair; add the case together with the locale.
 */
public enum PluralCategory {
	FEW, MANY, ONE, OTHER;

	/** Selects the cardinal category of {@code count} for a language code like "pl", "pt_BR", or "zh-CN". */
	public static PluralCategory select(String languageCode, long count) {
		String language = languageCode == null ? "" : languageCode.toLowerCase(Locale.ROOT).split("[-_]")[0];
		return switch (language) {
			// pl, szl: one is exactly 1; few is n%10 in 2..4 except n%100 in 12..14, so 13, 112, or 23423413 are many.
			case "pl", "szl" -> count == 1 ? ONE : slavicFewOrMany(count);
			// ru, uk: one is n%10 = 1 except n%100 = 11, so 21 or 1031 take the singular.
			case "ru", "uk" -> count % 10 == 1 && count % 100 != 11 ? ONE : slavicFewOrMany(count);
			// fr, pt: one also covers zero ("0 fichier", "0 arquivo").
			case "fr", "pt" -> count == 0 || count == 1 ? ONE : OTHER;
			// zh/ja/ko/th/vi have no cardinal plural agreement.
			case "zh", "ja", "ko", "th", "vi" -> OTHER;
			default -> count == 1 ? ONE : OTHER;
		};
	}

	private static PluralCategory slavicFewOrMany(long count) {
		long last = count % 10, lastTwo = count % 100;
		return last >= 2 && last <= 4 && !(lastTwo >= 12 && lastTwo <= 14) ? FEW : MANY;
	}

	/** The lang-key suffix this category selects, for example {@code keyBase + "." + category.keySuffix()}. */
	public String keySuffix() {
		return name().toLowerCase(Locale.ROOT);
	}
}
