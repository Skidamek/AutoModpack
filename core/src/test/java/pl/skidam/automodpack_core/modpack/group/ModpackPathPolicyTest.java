package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModpackPathPolicyTest {
	@Test
	void activeModClassificationRequiresBothTypeAndLoaderPath() {
		assertTrue(ModpackPathPolicy.isActiveMod("mods/main.jar", "mod"));
		assertTrue(ModpackPathPolicy.isActiveMod("mods/nested/main.jar", "mod"));
		assertFalse(ModpackPathPolicy.isActiveMod("mods/main.jar", "other"));
		assertFalse(ModpackPathPolicy.isActiveMod("config/main.jar", "mod"));
		assertFalse(ModpackPathPolicy.isActiveMod("mods/../config/main.jar", "mod"));
	}
}
