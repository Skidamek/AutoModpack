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
}
