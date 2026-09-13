package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LogicalPathTest {
	@Test
	void resolvesCanonicalPathWithinRoot() {
		assertEquals(Path.of("root/mods/example.jar"), LogicalPath.resolve(Path.of("root"), "mods/example.jar"));
	}

	@Test
	void rejectsTraversalAndNonCanonicalPaths() {
		assertThrows(IllegalArgumentException.class, () -> LogicalPath.resolve(Path.of("root"), "../outside"));
		assertThrows(IllegalArgumentException.class, () -> LogicalPath.resolve(Path.of("root"), "/mods/example.jar"));
		assertThrows(IllegalArgumentException.class, () -> LogicalPath.resolve(Path.of("root"), "mods/../example.jar"));
	}

	@Test
	void drivePrefixAcceptanceMatchesTheOldRegex() {
		String[] inputs = {null, "", "C", "C:", "C:/x", "c:/x", "z:/x", "1:/x", ":/x", "C:x", "/x", "//C:/x", "a\\b", "Ä:/x", "cc:/x", "cC:/x", "mods/x.jar", "a:/b/../c", "..\\x", "x/..",
				"C:/x\\y"};
		for (String input : inputs) assertSameNormalization(input);
	}

	private static void assertSameNormalization(String input) {
		try {
			assertEquals(oldNormalize(input), LogicalPath.normalize(input), String.valueOf(input));
		} catch (RuntimeException expected) {
			assertThrows(expected.getClass(), () -> LogicalPath.normalize(input), String.valueOf(input));
		}
	}

	/** The normalize as it was before the drive-prefix regex was hoisted, kept as the acceptance oracle. */
	private static String oldNormalize(String path) {
		if (path == null || path.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid logical path");
		String normalized = path.replace('\\', '/');
		while (normalized.startsWith("/")) normalized = normalized.substring(1);
		if (normalized.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("Unsafe logical path: " + path);
		Path value = Path.of(normalized).normalize();
		if (value.isAbsolute() || normalized.isBlank() || value.startsWith("..")) throw new IllegalArgumentException("Unsafe logical path: " + path);
		return value.toString().replace('\\', '/');
	}
}
