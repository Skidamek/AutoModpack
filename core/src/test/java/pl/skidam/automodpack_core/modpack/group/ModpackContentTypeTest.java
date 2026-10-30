package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModpackContentTypeTest {
	@Test
	void platformLookupFollowsJarsAndPackTrees() {
		assertTrue(ModpackContentType.needsPlatformLookup("mods/a.jar"));
		assertTrue(ModpackContentType.needsPlatformLookup("shaderpacks/x.zip"));
		assertTrue(ModpackContentType.needsPlatformLookup("resourcepacks/x.zip"));
		assertTrue(ModpackContentType.needsPlatformLookup("mods/mezz_config-standalone.jar"));
		assertTrue(ModpackContentType.needsPlatformLookup("config/lib.JAR"));
		assertFalse(ModpackContentType.needsPlatformLookup("config/settings.json"));
		assertFalse(ModpackContentType.needsPlatformLookup("options.txt"));
	}

	@Test
	void kindForPathUsesTheLogicalTree() {
		assertEquals(ModpackContentType.MOD, ModpackContentType.kindForPath("mods/a.jar"));
		assertEquals(ModpackContentType.SHADER, ModpackContentType.kindForPath("shaderpacks/x.zip"));
		assertEquals(ModpackContentType.RESOURCEPACK, ModpackContentType.kindForPath("resourcepacks/x.zip"));
		assertEquals(ModpackContentType.CONFIG, ModpackContentType.kindForPath("config/settings.json"));
		assertEquals(ModpackContentType.OTHER, ModpackContentType.kindForPath("kubejs/script.js"));
	}
}
