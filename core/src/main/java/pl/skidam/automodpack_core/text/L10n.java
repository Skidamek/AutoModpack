package pl.skidam.automodpack_core.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Resolves our own lang keys from the bundled {@code assets/automodpack/lang/<code>.json} resources, deliberately
 * not through the game's LanguageManager: mod lang files are only mergeable into it via FAPI's resource loader, and
 * a server-pushed resource pack could override the keys and puppeteer our UI text. Loads lazily per language, caches
 * parsed maps, and never fails: worst case a key comes back unresolved. Thread-safe (gui and server threads).
 *
 * <p>
 * A requested code resolves through the languages this jar actually ships: itself, then the same base language's
 * bundled variants, then {@code en_us}. The bundled files are the only list of languages there is, so a regional
 * translation added to the jar immediately serves every unsupported region of its language.
 */
public final class L10n {
	private static final Logger LOGGER = LogManager.getLogger(L10n.class);

	private static final String LANG_DIRECTORY = "/assets/automodpack/lang";
	private static final String LANG_PATH = LANG_DIRECTORY + "/%s.json";
	private static final String LANGUAGE_FILE_SUFFIX = ".json";
	private static final String FALLBACK_LANGUAGE = "en_us";
	/** A java-format specifier inside a lang value: '%s', '%2$s', '%.2f' - also the literal '%%'. */
	private static final Pattern FORMAT_SPEC = Pattern.compile("%(\\d+\\$)?[-#0 +]*\\d*(\\.\\d+)?[a-zA-Z%]");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
	private static final Set<String> NON_FALLBACK_LANGUAGES = Set.of("en_pt", "en_ud", "lol_us");
	private static final Set<String> TRADITIONAL_CHINESE_CODES = Set.of("zh_tw", "zh_hk", "zh_mo", "zh_hant");
	private static final Map<String, String> PREFERRED_VARIANT = Map.of("en", "en_us", "ko", "ko_kr", "uk", "uk_ua", "zh", "zh_cn");

	private static final Set<String> SHIPPED = discoverShippedLanguages();
	private static final Map<String, List<String>> CHAINS = new ConcurrentHashMap<>();
	private static final Map<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();

	private L10n() {}

	/** The resolved string for the key under the language chain {@code code -> its bundled variants -> en_us -> key}; args are java-format placeholders when present. */
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

	/** Whether the key resolves under the language chain {@code code -> its bundled variants -> en_us} - lets callers pick plural variants without rendering. */
	public static boolean has(String languageCode, String key) {
		return lookup(languageCode, key) != null;
	}

	public static boolean matchesPhrase(String input, String required) {
		if (input == null || required == null) return false;
		return normalizePhrase(input).equals(normalizePhrase(required));
	}

	private static String normalizePhrase(String value) {
		return WHITESPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC).strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
	}

	/** The game's language code for a system locale, preserving Traditional Chinese script when no country is set. */
	public static String languageFor(Locale locale) {
		if (locale == null) return FALLBACK_LANGUAGE;
		String language = locale.getLanguage().toLowerCase(Locale.ROOT);
		if (language.isBlank()) return FALLBACK_LANGUAGE;
		String country = locale.getCountry().toLowerCase(Locale.ROOT);
		if (!country.isBlank()) return language + "_" + country;
		if ("zh".equals(language) && "Hant".equalsIgnoreCase(locale.getScript())) return "zh_hant";
		return language;
	}

	/** The language the game was last set to; before Minecraft loads its own translations, its options file is the only record of it. */
	public static String languageFromOptions(Path optionsFile) {
		if (optionsFile != null && Files.isRegularFile(optionsFile)) {
			try (BufferedReader reader = Files.newBufferedReader(optionsFile, StandardCharsets.UTF_8)) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.startsWith("lang:")) {
						String value = line.substring("lang:".length()).trim();
						if (!value.isEmpty()) return value.toLowerCase(Locale.ROOT);
						break;
					}
				}
			} catch (IOException e) {
				LOGGER.debug("Could not read {} for preload language; using system locale", optionsFile, e);
			}
		}
		return languageFor(Locale.getDefault());
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
			for (String code : chain(languageCode.toLowerCase(Locale.ROOT))) {
				String value = language(code).get(key);
				if (value != null) return value;
			}
		}
		return language(FALLBACK_LANGUAGE).get(key);
	}

	private static List<String> chain(String code) {
		return CHAINS.computeIfAbsent(code, L10n::resolveChain);
	}

	/** The bundled languages one code resolves through, most specific first. */
	private static List<String> resolveChain(String code) {
		String base = baseLanguage(code);
		LinkedHashSet<String> chain = new LinkedHashSet<>();
		chain.add(code);
		if (TRADITIONAL_CHINESE_CODES.contains(code)) chain.add("zh_tw");
		else {
			String preferred = PREFERRED_VARIANT.get(base);
			if (preferred != null) chain.add(preferred);
		}
		if (!base.equals(code)) chain.add(base);
		for (String shipped : SHIPPED) {
			if (baseLanguage(shipped).equals(base) && !NON_FALLBACK_LANGUAGES.contains(shipped)) chain.add(shipped);
		}
		if (!PREFERRED_VARIANT.containsKey(base)) chain.add(base + "_" + base);
		return List.copyOf(chain);
	}

	private static String baseLanguage(String code) {
		int separator = code.indexOf('_');
		return separator < 0 ? code : code.substring(0, separator);
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

	/**
	 * The language codes this jar bundles, listed once from the resource directory. The anchor is the fallback file,
	 * which always exists, because a jar need not store its own directory entries. A classpath that cannot be listed
	 * any other way (an exotic loader layout) leaves the set empty, which only means an unsupported region falls back
	 * to English.
	 */
	private static Set<String> discoverShippedLanguages() {
		URL anchor = L10n.class.getResource(String.format(Locale.ROOT, LANG_PATH, FALLBACK_LANGUAGE));
		if (anchor == null) return Set.of();
		TreeSet<String> shipped = new TreeSet<>();
		try {
			if ("jar".equals(anchor.getProtocol())) {
				String prefix = LANG_DIRECTORY.substring(1) + "/";
				JarURLConnection connection = (JarURLConnection) anchor.openConnection();
				connection.setUseCaches(false);
				try (JarFile jar = connection.getJarFile()) {
					for (JarEntry entry : Collections.list(jar.entries())) {
						if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
						addLanguage(shipped, entry.getName().substring(prefix.length()));
					}
				}
				return Set.copyOf(shipped);
			}
			Path directory = Path.of(anchor.toURI()).getParent();
			if (directory != null && Files.isDirectory(directory)) {
				try (Stream<Path> files = Files.list(directory)) {
					files.forEach(file -> addLanguage(shipped, file.getFileName().toString()));
				}
				return Set.copyOf(shipped);
			}
		} catch (Exception e) {
			LOGGER.debug("Could not list the bundled languages; unsupported regions fall back to en_us", e);
		}
		return Set.of();
	}

	private static void addLanguage(Set<String> shipped, String fileName) {
		if (!fileName.endsWith(LANGUAGE_FILE_SUFFIX)) return;
		shipped.add(fileName.substring(0, fileName.length() - LANGUAGE_FILE_SUFFIX.length()).toLowerCase(Locale.ROOT));
	}
}
