package pl.skidam.automodpack_core.text;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Resolves our own lang keys from the bundled {@code assets/automodpack/lang/<code>.json} resources, deliberately
 * not through the game's LanguageManager: mod lang files are only mergeable into it via FAPI's resource loader, and
 * a server-pushed resource pack could override the keys and puppeteer our UI text. Loads lazily per language, caches
 * parsed maps, and never fails: worst case a key comes back unresolved. Thread-safe (gui and server threads).
 */
public final class L10n {
	private static final String LANG_PATH = "/assets/automodpack/lang/%s.json";
	private static final String FALLBACK_LANGUAGE = "en_us";
	/** A java-format specifier inside a lang value: '%s', '%2$s', '%.2f' - also the literal '%%'. */
	private static final Pattern FORMAT_SPEC = Pattern.compile("%(\\d+\\$)?[-#0 +]*\\d*(\\.\\d+)?[a-zA-Z%]");
	private static final Map<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();

	private L10n() {}

	/** The resolved string for the key under the language chain {@code code -> base language -> en_us -> key}; args are java-format placeholders when present. */
	public static String get(String languageCode, String key, Object... args) {
		String value = lookup(languageCode, key);
		if (value == null) return key;
		if (args.length == 0) return value;
		try {
			return String.format(Locale.ROOT, value, args);
		} catch (IllegalFormatException e) {
			// A translation with a stray '%' must render as itself, not take the screen down with it.
			LOGGER.warn("Lang entry '{}' is not a valid format string: {}", key, value, e);
			return value;
		}
	}

	/** Whether the key resolves under the language chain {@code code -> base language -> en_us} - lets callers pick plural variants without rendering. */
	public static boolean has(String languageCode, String key) {
		return lookup(languageCode, key) != null;
	}

	/**
	 * The keys whose {@code en_us} value is exactly {@code value} - the reverse of {@link #get} for the test bridge's
	 * stable element matching (our UI renders literals, so a translation key only exists in the lang table). Entries
	 * with java-format placeholders ('Keep %s existing mod files') match by template against the rendered value. Sorted;
	 * several keys may legitimately match one value, and the caller treats the result as a set.
	 */
	public static List<String> keysFor(String value) {
		if (value == null || value.isEmpty()) return List.of();
		Map<String, String> english = language(FALLBACK_LANGUAGE);
		List<String> exact = english.entrySet().stream().filter(entry -> value.equals(entry.getValue())).map(Map.Entry::getKey).sorted().toList();
		if (!exact.isEmpty()) return exact;
		return english.entrySet().stream().filter(entry -> entry.getValue().indexOf('%') >= 0).filter(entry -> templateMatches(entry.getValue(), value)).map(Map.Entry::getKey).sorted().toList();
	}

	/** Whether {@code value} is the rendered form of {@code template}: each format specifier stands for any non-empty text. */
	private static boolean templateMatches(String template, String value) {
		String[] literalParts = FORMAT_SPEC.split(template, -1);
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < literalParts.length; i++) {
			regex.append(Pattern.quote(literalParts[i]));
			if (i < literalParts.length - 1) regex.append(".+");
		}
		return Pattern.matches(regex.toString(), value);
	}

	private static String lookup(String languageCode, String key) {
		if (languageCode != null && !languageCode.isBlank()) {
			String code = languageCode.toLowerCase(Locale.ROOT);
			String value = language(code).get(key);
			if (value != null) return value;
			int underscore = code.indexOf('_');
			if (underscore > 0) {
				value = language(code.substring(0, underscore)).get(key);
				if (value != null) return value;
			}
		}
		return language(FALLBACK_LANGUAGE).get(key);
	}

	private static Map<String, String> language(String code) {
		return LANGUAGES.computeIfAbsent(code, L10n::load);
	}

	private static Map<String, String> load(String code) {
		InputStream stream = L10n.class.getResourceAsStream(String.format(Locale.ROOT, LANG_PATH, code));
		if (stream == null) return Map.of();
		try (stream) {
			JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, String> entries = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
				entries.put(entry.getKey(), entry.getValue().getAsString());
			}
			return Map.copyOf(entries);
		} catch (Exception e) {
			LOGGER.warn("Bundled lang file for '{}' is unreadable, falling back for every key in it", code, e);
			return Map.of();
		}
	}
}
