package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
