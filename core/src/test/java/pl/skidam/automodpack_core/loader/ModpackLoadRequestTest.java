package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class ModpackLoadRequestTest {
	@Test
	void carriesOneNormalizedRootAndStableNestedPaths() {
		Path root = Path.of("build", "active", "mods");
		Path nested = root.resolve("nested/b.jar");
		Path direct = root.resolve("a.jar");

		ModpackLoadRequest request = new ModpackLoadRequest(root, List.of(nested, direct, nested));

		assertEquals(root.toAbsolutePath().normalize(), request.activeModsDirectory());
		assertEquals(List.of(direct.toAbsolutePath().normalize(), nested.toAbsolutePath().normalize()), request.modpackMods());
	}

	@Test
	void rejectsPathsOutsideTheExplicitLoaderRoot() {
		Path root = Path.of("build", "active", "mods");

		assertThrows(IllegalArgumentException.class, () -> new ModpackLoadRequest(root, List.of(root.resolveSibling("escape.jar"))));
		assertThrows(IllegalArgumentException.class, () -> new ModpackLoadRequest(root, List.of(root)));
	}

	@Test
	void allowsJarsOutsideTheProjectionAndOnlyRequestedProjectionJars() {
		Path root = Path.of("build", "active", "mods");
		Path requested = root.resolve("keep.jar");
		Path skipped = root.resolve("skip.jar");
		Path live = root.resolveSibling("live.jar");
		ModpackLoadRequest request = new ModpackLoadRequest(root, List.of(requested));

		assertTrue(request.allowsProjectionJar(requested));
		assertFalse(request.allowsProjectionJar(skipped));
		assertTrue(request.allowsProjectionJar(live));
	}
}
