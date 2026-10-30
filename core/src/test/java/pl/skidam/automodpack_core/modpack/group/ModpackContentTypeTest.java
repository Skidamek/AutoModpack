package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModpackContentTypeTest {
	@Test
	void platformLookupFollowsTheConfirmScreenJarSet() {
		assertTrue(ModpackContentType.needsPlatformLookup(ModpackContentType.MOD, "mods/a.jar"));
		assertTrue(ModpackContentType.needsPlatformLookup(ModpackContentType.SHADER, "shaderpacks/x.zip"));
		assertTrue(ModpackContentType.needsPlatformLookup(ModpackContentType.RESOURCEPACK, "resourcepacks/x.zip"));
		assertTrue(ModpackContentType.needsPlatformLookup(ModpackContentType.OTHER, "mods/mezz_config-standalone.jar"));
		assertTrue(ModpackContentType.needsPlatformLookup(ModpackContentType.OTHER, "config/lib.JAR"));
		assertFalse(ModpackContentType.needsPlatformLookup(ModpackContentType.OTHER, "config/settings.json"));
		assertFalse(ModpackContentType.needsPlatformLookup(ModpackContentType.CONFIG, "config/settings.json"));
		assertFalse(ModpackContentType.needsPlatformLookup(ModpackContentType.MINECRAFT_OPTIONS, "options.txt"));
	}
}
