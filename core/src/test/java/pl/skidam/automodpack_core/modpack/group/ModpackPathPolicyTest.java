package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModpackPathPolicyTest {
	@Test
	void activeModIsAJarUnderMods() {
		assertTrue(ModpackPathPolicy.isActiveMod("mods/main.jar"));
		assertTrue(ModpackPathPolicy.isActiveMod("mods/nested/main.jar"));
		assertFalse(ModpackPathPolicy.isActiveMod("mods/README.txt"));
		assertFalse(ModpackPathPolicy.isActiveMod("config/main.jar"));
		assertFalse(ModpackPathPolicy.isActiveMod("mods/../config/main.jar"));
	}

	@Test
	void packsCannotClaimInstanceRootBootstrapFile() {
		assertFalse(ModpackPathPolicy.isValidPublishedPath("automodpack-bootstrap.json"));
		assertFalse(ModpackPathPolicy.isValidPublishedPath("automodpack/automodpack-bootstrap.json"));
	}
}
