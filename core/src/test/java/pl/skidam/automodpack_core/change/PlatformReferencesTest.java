package pl.skidam.automodpack_core.change;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.platforms.CurseForgeAPI;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_core.utils.cache.PlatformMetadataCache;

class PlatformReferencesTest {
	private static final String SHA1 = "1111111111111111111111111111111111111111";
	private static final String MODRINTH_PAGE = "https://modrinth.com/mod/example";
	private static final String CURSEFORGE_PAGE = "https://www.curseforge.com/minecraft/mc-mods/example";

	@TempDir
	Path temporaryDirectory;

	@Test
	void attachesCachedPagesWhenACatalogueOccurrenceHasOnlyAnAfterHash() throws Exception {
		try (PlatformMetadataCache cache = PlatformMetadataCache.open(temporaryDirectory)) {
			cache.putModrinth(SHA1, new ModrinthAPI("example", null, "https://cdn.modrinth.com/data/example/file.jar", "1", "example.jar", 1, "release", SHA1), MODRINTH_PAGE);
			cache.putCurseForge(SHA1, new CurseForgeAPI(null, "https://edge.forgecdn.net/file.jar", "1", "example.jar", "1", "release", "1", SHA1, 12, CURSEFORGE_PAGE));
			ChangeSet referenced = PlatformReferences.withCachedReferences(catalogue(), cache);
			assertEquals(List.of(MODRINTH_PAGE, CURSEFORGE_PAGE), referenced.changes().get(0).occurrences().get(0).references());
		}
	}

	@Test
	void pathLookupSurvivesCatalogueOccurrencesWithANullBeforeHash() throws Exception {
		try (PlatformMetadataCache cache = PlatformMetadataCache.open(temporaryDirectory)) {
			cache.putModrinth(SHA1, new ModrinthAPI("example", null, "https://cdn.modrinth.com/data/example/file.jar", "1", "example.jar", 1, "release", SHA1), MODRINTH_PAGE);
		}
		ChangeSet referenced = PlatformReferences.withCachedReferences(catalogue(), temporaryDirectory);
		assertEquals(List.of(MODRINTH_PAGE), referenced.changes().get(0).occurrences().get(0).references());
	}

	private static ChangeSet catalogue() {
		GroupManifest.GroupFile file = new GroupManifest.GroupFile(1, "mod", false, SHA1, null);
		GroupManifest.Group group = new GroupManifest.Group("Main", "", "", true, true, new TreeSet<>(), new TreeSet<>(), Set.of(), new TreeMap<>(Map.of("mods/example.jar", file)));
		return ChangeSet.catalogue(new GroupManifest("pack", "Pack", "1", "fabric", "1", "1.21", new TreeMap<>(Map.of("main", group))));
	}
}
