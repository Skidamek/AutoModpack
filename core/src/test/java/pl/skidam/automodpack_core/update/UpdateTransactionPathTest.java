package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The canonical fast path returns exactly what the logical-path fallback would, and refuses the same shapes. */
class UpdateTransactionPathTest {
	@Test
	void canonicalPathsAreReturnedUnchanged() throws IOException {
		for (String path : List.of("mods/a.jar", "config/x.toml", ".hidden", "dir.with.dots/file-1_2.jar", "Mods/UPPER.JAR", "a", "a/b/c/d.txt"))
			assertEquals(path, UpdateTransactionValidator.normalizeOperationPath(path));
	}

	@Test
	void backslashSeparatedPathsStillNormalize() throws IOException {
		assertEquals("mods/a.jar", UpdateTransactionValidator.normalizeOperationPath("mods\\a.jar"));
		assertEquals("a/b", UpdateTransactionValidator.normalizeOperationPath("a\\b"));
	}

	@Test
	void nonCanonicalPathsAreRefused() {
		for (String path : List.of("mods//a.jar", "mods/./a.jar", "mods/../a.jar", "mods/", ".")) assertRefused("Path is not normalized: " + path, path);
	}

	@Test
	void absoluteAndDrivePathsAreRefusedAsRelative() {
		for (String path : List.of("/mods/a.jar", "\\mods\\a.jar", "C:/mods/a.jar", "c:\\mods\\a.jar"))
			assertRefused("Operation path must be relative", path);
	}

	@Test
	void unsafePathsAreRefused() {
		for (String path : List.of("..", "", "C:relative", "a\0b"))
			assertRefused("Unsafe operation path", path);
	}

	private static void assertRefused(String expectedPrefix, String path) {
		IOException refused = assertThrows(IOException.class, () -> UpdateTransactionValidator.normalizeOperationPath(path));
		assertTrue(refused.getMessage().startsWith(expectedPrefix), path + " -> " + refused.getMessage());
	}
}
