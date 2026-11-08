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

import org.junit.jupiter.api.Test;

/**
 * Ratchet on cross-domain imports in {@code core/src/main/java}. The baseline records every edge that exists today, and a new one fails the build until it is added on purpose.
 * Domains are the first package segment under {@code automodpack_core}; types declared directly in {@code automodpack_core} belong to {@code <root>}.
 * The baseline only ever grows deliberately: an edge that disappears may stay listed, which over-permits exactly one entry and changes nothing.
 */
class PackageEdgesTest {
	private static final String BASELINE = "/architecture/package-edges.txt";
	private static final String CORE = "pl.skidam.automodpack_core";
	private static final String CYCLE_BUDGET = "/architecture/cycle-budget.txt";
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

	/** A pair of domains that import each other; the number may only go down, so a new cycle needs a deliberate budget change. */
	@Test
	void crossDomainCyclesDoNotGrow() throws IOException {
		Set<String> edges = currentEdges();
		int cycles = 0;
		for (String edge : edges) {
			String[] ends = edge.split(" -> ");
			if (edges.contains(ends[1] + " -> " + ends[0])) cycles++;
		}
		cycles /= 2;
		int budget = cycleBudget();
		if (cycles <= budget) return;
		fail("core main has " + cycles + " mutually importing domain pairs, the budget is " + budget + ". Remove an edge instead of raising core/src/test/resources" + CYCLE_BUDGET + ": "
				+ String.join(", ", cycles(edges)));
	}

	private static List<String> cycles(Set<String> edges) {
		List<String> cycles = new ArrayList<>();
		for (String edge : edges) {
			String[] ends = edge.split(" -> ");
			if (ends[0].compareTo(ends[1]) < 0 && edges.contains(ends[1] + " -> " + ends[0])) cycles.add(edge + " and " + ends[1] + " -> " + ends[0]);
		}
		return cycles;
	}

	private static int cycleBudget() throws IOException {
		try (InputStream stream = PackageEdgesTest.class.getResourceAsStream(CYCLE_BUDGET)) {
			if (stream == null) throw new IllegalStateException("Missing budget resource " + CYCLE_BUDGET);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).mapToInt(Integer::parseInt).findFirst()
					.orElseThrow(() -> new IllegalStateException("Empty cycle budget " + CYCLE_BUDGET));
		}
	}

	private static Set<String> currentEdges() throws IOException {
		List<String> texts = new ArrayList<>();
		for (Path source : CoreSources.javaFiles()) texts.add(Files.readString(source, StandardCharsets.UTF_8));
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

}
