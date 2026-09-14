package pl.skidam.automodpack_core.text;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
	private static final Map<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();

	private L10n() {}

	/** The resolved string for the key under the language chain {@code code -> base language -> en_us -> key}; args are java-format placeholders when present. */
	public static String get(String languageCode, String key, Object... args) {
		String value = resolve(languageCode, key);
		if (args.length == 0) return value;
		try {
			return String.format(Locale.ROOT, value, args);
		} catch (IllegalFormatException e) {
			// A translation with a stray '%' must render as itself, not take the screen down with it.
			LOGGER.warn("Lang entry '{}' is not a valid format string: {}", key, value, e);
			return value;
		}
	}

	private static String resolve(String languageCode, String key) {
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
		String value = language(FALLBACK_LANGUAGE).get(key);
		return value != null ? value : key;
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
