package pl.skidam.automodpack_core.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Ratchet on cross-domain imports in {@code core/src/main/java}. The baseline records every edge that exists today, and a new one fails the build until it is added on purpose.
 * Domains are the first package segment under {@code automodpack_core}; types declared directly in {@code automodpack_core} belong to {@code <root>}.
 * The baseline only ever grows deliberately: an edge that disappears may stay listed, which over-permits exactly one entry and changes nothing.
 */
class PackageEdgesTest {
	private static final String BASELINE = "/architecture/package-edges.txt";
	private static final String CORE = "pl.skidam.automodpack_core";
	private static final String ROOT = "<root>";
	private static final String NEWLINE = "\n";
	private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
	private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?pl\\.skidam\\.automodpack_core\\.([\\w.]+?)(?:\\.\\*)?\\s*;", Pattern.MULTILINE);

	@Test
	void coreMainHasNoNewCrossDomainEdges() throws IOException {
		Set<String> added = new TreeSet<>(currentEdges());
		added.removeAll(baseline());
		if (added.isEmpty()) return;
		fail("New cross-domain import edge(s) in core main: " + String.join(", ", added) + NEWLINE + "If the dependency is deliberate, add these lines to core/src/test/resources" + BASELINE + " in the same commit: "
				+ String.join(NEWLINE, added));
	}

	private static Set<String> currentEdges() throws IOException {
		List<Path> sources = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(coreMain())) {
			walk.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
		}
		List<String> texts = new ArrayList<>();
		for (Path source : sources) texts.add(Files.readString(source, StandardCharsets.UTF_8));
		Set<String> domains = new HashSet<>();
		for (String text : texts) {
			Matcher matcher = PACKAGE.matcher(text);
			if (matcher.find()) domains.add(domain(matcher.group(1)));
		}
		Set<String> edges = new TreeSet<>();
		for (String text : texts) {
			Matcher own = PACKAGE.matcher(text);
			if (!own.find()) continue;
			String from = domain(own.group(1));
			Matcher imported = IMPORT.matcher(text);
			while (imported.find()) {
				String first = imported.group(1).split("\\.")[0];
				String to = domains.contains(first) ? first : ROOT;
				if (!from.equals(to)) edges.add(from + " -> " + to);
			}
		}
		return edges;
	}

	private static String domain(String pkg) {
		String rest = pkg.equals(CORE) ? "" : pkg.startsWith(CORE + ".") ? pkg.substring(CORE.length() + 1) : pkg;
		return rest.isEmpty() ? ROOT : rest.split("\\.")[0];
	}

	private static Set<String> baseline() throws IOException {
		try (InputStream stream = PackageEdgesTest.class.getResourceAsStream(BASELINE)) {
			if (stream == null) throw new IllegalStateException("Missing baseline resource " + BASELINE);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).collect(Collectors.toCollection(TreeSet::new));
		}
	}

	private static Path coreMain() throws IOException {
		Path root = Path.of("").toAbsolutePath();
		while (root != null && !Files.isRegularFile(root.resolve("settings.gradle.kts"))) root = root.getParent();
		if (root == null) throw new IllegalStateException("Could not locate the repository root (settings.gradle.kts) from " + Path.of("").toAbsolutePath());
		Path coreMain = root.resolve("core/src/main/java");
		if (!Files.isDirectory(coreMain)) throw new IllegalStateException("Missing core main sources at " + coreMain);
		return coreMain;
	}
}
