package pl.skidam.automodpack_core.text;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tripwire for raw translation keys reaching the screen: our UI renders {@code automodpack.*} keys through {@link L10n}
 * from the bundled lang table (the game's language manager has no automodpack entries, so a key that misses the table
 * renders as itself). Scans every quoted {@code automodpack.*} literal in the java sources and asserts it exists in
 * {@code en_us.json} - exactly, or as a dotted prefix of an existing key, which is how the dynamic construction sites
 * ({@code "automodpack.summary.kind." + kind} and the {@code UiFormat.plural} {@code .one/.other} bases) build keys.
 * A literal must be listed in NOT_LANG_KEYS to opt out; that list is the hand audit for strings that only borrow the
 * namespace, like system properties.
 */
class LangKeyTripwireTest {
	private static final Pattern KEY_IN_SOURCE = Pattern.compile("\"(automodpack\\.[A-Za-z0-9_.]+)\"");

	/** Our keys must go through {@code VersionedText.text}; {@code VersionedText.translatable} is for vanilla keys only (the vanilla table has no automodpack entries). */
	private static final Pattern OUR_KEY_TO_VANILLA_TRANSLATION = Pattern.compile("VersionedText\\.translatable\\([^)\"]*\"(automodpack\\.[A-Za-z0-9_.]+)\"");

	/** Strings that use the namespace but are not translation keys. */
	private static final Set<String> NOT_LANG_KEYS = Set.of("automodpack.autotest.gamedir", "automodpack.autotest.render", "automodpack.autotest.token", "automodpack.data.root");

	@Test
	void everyKeyInCodeResolvesFromTheBundledLangTable() throws IOException {
		Path root = repoRoot();
		if (root == null) return;
		Map<String, String> english = englishKeys(root);
		List<String> unresolved = keysInSources(root).stream()
				.filter(key -> !NOT_LANG_KEYS.contains(key))
				.filter(key -> !english.containsKey(key) && english.keySet().stream().noneMatch(existing -> existing.startsWith(prefixOf(key))))
				.toList();
		assertTrue(unresolved.isEmpty(), () -> "Keys used in code but missing from en_us.json (every locale renders these raw): " + unresolved);
	}

	@Test
	void automodpackKeysNeverResolveThroughVanillaTranslation() throws IOException {
		Path root = repoRoot();
		if (root == null) return;
		List<String> sites = new ArrayList<>();
		for (String module : List.of("src", "core", "loader")) {
			Path tree = root.resolve(module);
			if (!Files.isDirectory(tree)) continue;
			try (Stream<Path> files = Files.walk(tree)) {
				files.filter(path -> path.getFileName().toString().endsWith(".java") && underSources(path)).forEach(path -> vanillaTranslatedKeys(path, sites));
			}
		}
		assertTrue(sites.isEmpty(), () -> "Call sites passing automodpack.* keys to vanilla translatable (renders as the raw key): " + sites);
	}

	private static void vanillaTranslatedKeys(Path file, List<String> sites) {
		try {
			Matcher matcher = OUR_KEY_TO_VANILLA_TRANSLATION.matcher(Files.readString(file, StandardCharsets.UTF_8));
			while (matcher.find()) sites.add(file.getFileName() + ": " + matcher.group(1));
		} catch (IOException e) {
			throw new IllegalStateException("Unreadable source " + file, e);
		}
	}

	/** The prefix an existing key must carry: the literal itself when it already ends at a dot boundary (dynamic concatenation sites), else the literal plus one. */
	private static String prefixOf(String key) {
		return key.endsWith(".") ? key : key + ".";
	}

	/** The checkout root, found from the core module's test working directory; absent when core runs standalone. */
	private static Path repoRoot() {
		Path root = Path.of("").toAbsolutePath();
		while (root != null && !Files.isDirectory(root.resolve("versions"))) root = root.getParent();
		return root;
	}

	private static Map<String, String> englishKeys(Path root) throws IOException {
		try (InputStream input = Files.newInputStream(root.resolve("src").resolve("main").resolve("resources").resolve("assets").resolve("automodpack").resolve("lang").resolve("en_us.json"))) {
			JsonObject json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, String> keys = new HashMap<>();
			for (var entry : json.entrySet()) keys.put(entry.getKey(), entry.getValue().getAsString());
			return keys;
		}
	}

	private static List<String> keysInSources(Path root) throws IOException {
		List<String> keys = new ArrayList<>();
		for (String module : List.of("src", "core", "loader")) {
			Path tree = root.resolve(module);
			if (!Files.isDirectory(tree)) continue;
			try (Stream<Path> files = Files.walk(tree)) {
				files.filter(path -> path.getFileName().toString().endsWith(".java") && underSources(path)).map(LangKeyTripwireTest::keysIn)
						.flatMap(List::stream)
						.filter(key -> !keys.contains(key))
						.forEach(keys::add);
			}
		}
		return keys.stream().distinct().sorted().toList();
	}

	/** Only the shipped sources (src/main/java trees), not build outputs or tests; separator-free so Windows paths match too. */
	private static boolean underSources(Path path) {
		String normalized = path.toString().replace('\\', '/');
		return normalized.contains("/src/main/java/") && !normalized.contains("/build/") && !normalized.contains("/versions/");
	}

	private static List<String> keysIn(Path file) {
		try {
			Matcher matcher = KEY_IN_SOURCE.matcher(Files.readString(file, StandardCharsets.UTF_8));
			List<String> keys = new ArrayList<>();
			while (matcher.find()) keys.add(matcher.group(1));
			return keys;
		} catch (IOException e) {
			throw new IllegalStateException("Unreadable source " + file, e);
		}
	}
}
