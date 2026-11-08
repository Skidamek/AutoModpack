package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class CanonicalPathPolicyTest {
	@Test
	void internalChecksAgreeForAlreadyCanonicalPaths() {
		for (String path : List.of("mods/main.jar", "mods/MAIN.JAR", "mods/nested/main.jar", "mods/notes.txt", "config/main.json", "Mods/main.jar", "automodpack/state.json", "saves/world.dat", "options.txt",
				"mods/automodpack-generated.jar", "automodpack-bootstrap.json")) {
			LogicalPath.requireCanonical(path);
			assertEquals(ModpackPathPolicy.isActiveMod(path), ModpackPathPolicy.isActiveCanonicalMod(path));
			assertEquals(ModpackPathPolicy.isValidPublishedPath(path), ModpackPathPolicy.isValidCanonicalPublishedPath(path));
		}
		assertEquals(LogicalPath.normalize("mods/automodpack-generated.jar"), ModpackPathPolicy.generatedBundlePath());
	}

	@Test
	void publicRawInputEntryPointsStillRejectNonCanonicalPaths() {
		for (String path : List.of("/mods/main.jar", "mods/../config/main.jar", "mods//main.jar", "mods/./main.jar", "mods\\main.jar", "../mods/main.jar", "")) {
			assertFalse(ModpackPathPolicy.isActiveMod(path));
			assertFalse(ModpackPathPolicy.isValidPublishedPath(path));
		}
		assertFalse(ModpackPathPolicy.isActiveMod(null));
		assertFalse(ModpackPathPolicy.isValidPublishedPath(null));
	}
}
