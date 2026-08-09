package pl.skidam.automodpack_core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Owns AutoModpack's client-local preference in Minecraft's options.txt. */
public final class ClientOptionsPreference {
	public static final String SKIP_REVIEW_KEY = "automodpack.skipReview";

	private static final Object LOCK = new Object();
	private static Path optionsFile;
	private static boolean skipReview;

	private ClientOptionsPreference() {}

	/** Loads the preference once Minecraft has established the active options.txt path. */
	public static void load(Path file) {
		Path normalized = file.toAbsolutePath().normalize();
		synchronized (LOCK) {
			optionsFile = normalized;
			skipReview = readSkipReview(normalized);
		}
	}

	public static boolean skipReview() {
		synchronized (LOCK) {
			return skipReview;
		}
	}

	/** Changes and immediately persists the local preference. Server data never calls this method. */
	public static void setSkipReview(boolean enabled) throws IOException {
		synchronized (LOCK) {
			if (optionsFile == null) throw new IllegalStateException("Minecraft options.txt has not been loaded");
			writeSkipReview(optionsFile, enabled);
			skipReview = enabled;
		}
	}

	/** Re-applies the cached local value after another subsystem rewrites options.txt. */
	public static void persistConfiguredFile() throws IOException {
		synchronized (LOCK) {
			if (optionsFile != null) writeSkipReview(optionsFile, skipReview);
		}
	}

	public static boolean readSkipReview(Path file) {
		if (!Files.isRegularFile(file)) return false;
		try {
			boolean found = false;
			boolean value = false;
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String parsed = valueForKey(line);
				if (parsed == null) continue;
				if (found || !(parsed.equalsIgnoreCase("true") || parsed.equalsIgnoreCase("false"))) return false;
				found = true;
				value = Boolean.parseBoolean(parsed);
			}
			return found && value;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	public static void writeSkipReview(Path file, boolean enabled) throws IOException {
		List<String> lines = Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
		List<String> rewritten = new ArrayList<>(lines.size() + 1);
		for (String line : lines) if (valueForKey(line) == null) rewritten.add(line);
		rewritten.add(SKIP_REVIEW_KEY + ":" + enabled);
		Path parent = file.toAbsolutePath().normalize().getParent();
		if (parent != null) Files.createDirectories(parent);
		AtomicFileWriter.write(file, (String.join("\n", rewritten) + "\n").getBytes(StandardCharsets.UTF_8));
	}

	private static String valueForKey(String line) {
		int separator = line.indexOf(':');
		return separator >= 0 && line.substring(0, separator).equals(SKIP_REVIEW_KEY) ? line.substring(separator + 1).trim() : null;
	}
}
