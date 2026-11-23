package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
		assertTrue(request.isProjectionJar(skipped));
		assertFalse(request.isProjectionJar(live));
	}

	/**
	 * A loader discovers jars through the real path of the projection, so a game directory reached through a link or a
	 * junction (relocated instances) must still read as the same projection - otherwise every projection jar reads as a
	 * live jar and the pin, live-path, hash, and editable exclusions all silently stop applying.
	 */
	@Test
	void readsAProjectionReachedThroughALinkAsTheSameProjection(@TempDir Path temp) throws IOException {
		Path real = Files.createDirectories(temp.resolve("real").resolve("active").resolve("mods"));
		Path requested = Files.writeString(real.resolve("requested.jar"), "requested");
		Path skipped = Files.writeString(real.resolve("skipped.jar"), "skipped");
		Path link;
		try {
			link = Files.createSymbolicLink(temp.resolve("instance"), real.getParent().getParent());
		} catch (IOException | UnsupportedOperationException unavailable) {
			Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + unavailable);
			return;
		}

		ModpackLoadRequest request = new ModpackLoadRequest(link.resolve("active").resolve("mods"), List.of(link.resolve("active").resolve("mods").resolve("requested.jar")));

		assertTrue(request.allowsProjectionJar(requested));
		assertFalse(request.allowsProjectionJar(skipped));
		assertTrue(request.isProjectionJar(skipped));
		Path live = Files.createDirectories(real.resolveSibling("live")).resolve("live.jar");
		assertFalse(request.isProjectionJar(live));
		assertTrue(request.allowsProjectionJar(live));
	}
}
