package pl.skidam.automodpack_core.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Keeps the process seams out of engine logic. A seam is read at a composition root ({@code Server}, {@code Preload}) or at
 * the one platform boundary that owns it ({@code ModpackExecutor}'s default wiring, {@code Secrets}' game call,
 * {@code SelfUpdater}'s storage lookup); every other reader takes the collaborator as a parameter.
 * The baseline records where the seams are read today, and a new read fails the build until it is added on purpose.
 */
class ProcessSeamTest {
	private static final String BASELINE = "/architecture/seam-reads.txt";

	/** Each seam and the expression that reads it; prose mentions cannot match because an identifier must follow the dot. */
	private static final Map<String, Pattern> SEAMS = seams();

	@Test
	void coreMainReadsProcessSeamsOnlyWhereAllowed() throws IOException {
		Set<String> added = new TreeSet<>(currentReads());
		added.removeAll(baseline());
		if (added.isEmpty()) return;
		fail("New read of a process seam inside core main: " + String.join(", ", added) + NEWLINE + "Take the collaborator as a parameter, or add the line to core/src/test/resources" + BASELINE
				+ " when this is a composition root or a platform boundary: " + String.join(NEWLINE, added));
	}

	private static Map<String, Pattern> seams() {
		Map<String, Pattern> seams = new LinkedHashMap<>();
		seams.put("Hosting", Pattern.compile("\\bHosting\\s*\\.\\s*[A-Za-z_]"));
		seams.put("LoaderServices", Pattern.compile("\\bLoaderServices\\s*\\.\\s*[A-Za-z_]"));
		seams.put("ClientStorage.current", Pattern.compile("\\bClientStorage\\s*\\.\\s*current\\s*\\("));
		return seams;
	}

	private static Set<String> currentReads() throws IOException {
		Path main = CoreSources.main();
		Set<String> reads = new TreeSet<>();
		for (Path file : CoreSources.javaFiles()) {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			String path = main.relativize(file).toString().replace('\\', '/');
			for (Map.Entry<String, Pattern> seam : SEAMS.entrySet()) {
				Matcher matcher = seam.getValue().matcher(text);
				if (matcher.find()) reads.add(seam.getKey() + " " + path);
			}
		}
		return reads;
	}

	private static Set<String> baseline() throws IOException {
		try (InputStream stream = ProcessSeamTest.class.getResourceAsStream(BASELINE)) {
			if (stream == null) throw new IllegalStateException("Missing baseline resource " + BASELINE);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).collect(Collectors.toCollection(TreeSet::new));
		}
	}

	private static final String NEWLINE = "\n";
}
