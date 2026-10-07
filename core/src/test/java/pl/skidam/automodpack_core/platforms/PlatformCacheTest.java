package pl.skidam.automodpack_core.platforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformCacheTest {
	private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

	@TempDir
	Path temporaryDirectory;

	@Test
	void modrinthServerOnlyEnvironmentsKeepTheJarOffClients() throws IOException {
		assertEquals("modrinth environment server_only", reason(modrinth("server_only")));
		assertEquals("modrinth environment dedicated_server_only", reason(modrinth("Dedicated_Server_Only")));
	}

	@Test
	void aModrinthEnvironmentDecidesEvenWhenItDecidesNothing() throws IOException {
		assertNull(reason(modrinth("unknown")));
		assertNull(reason(modrinth("client_and_server")));
		assertNull(reason(modrinth("server_only_client_optional")));
	}

	@Test
	void curseForgeSideTagsOnlyFillAMissingModrinthField() throws IOException {
		assertEquals("curseforge gameVersions Server", reason(null, curseForge(false, true)));
		assertEquals("curseforge gameVersions Server", reason(modrinth(""), curseForge(false, true)));
		assertNull(reason(null, curseForge(true, true)));
		assertNull(reason(null, curseForge(false, false)));
		assertNull(reason(null, null));
	}

	/** The project's public page is a client-reference concern; the side tags are a pack-content concern and outlive the page. */
	@Test
	void anUnlistedCurseForgeProjectKeepsItsSideVerdict() throws IOException {
		String reason = reason(null, new CurseForgeAPI(null, "https://cdn.example/a.jar", "1.0.0", "a.jar", "1", "release", "12345", SHA1, 7, "https://www.curseforge.com/minecraft/mc-mods/project-7", false, true));

		assertEquals("curseforge gameVersions Server", reason, "an unlisted project page must not cost the file its client-side verdict");
	}

	/** Records come back under the normalized digest, so a caller may ask in any case. */
	@Test
	void recordsAreKeyedByTheNormalizedDigest() throws IOException {
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory.resolve("platform-cache"))) {
			cache.putModrinth(SHA1, modrinth("server_only"), null);

			assertEquals("modrinth environment server_only", cache.getAll(List.of(SHA1.toUpperCase(Locale.ROOT))).get(SHA1).clientExclusionReason());
		}
	}

	private String reason(ModrinthAPI modrinth) throws IOException {
		return reason(modrinth, null);
	}

	private String reason(ModrinthAPI modrinth, CurseForgeAPI curseForge) throws IOException {
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory.resolve("platform-cache"))) {
			cache.putModrinth(SHA1, modrinth, null);
			if (curseForge != null) cache.putCurseForge(SHA1, curseForge);
			PlatformCache.Record record = cache.getAll(List.of(SHA1)).get(SHA1);
			return record == null ? null : record.clientExclusionReason();
		}
	}

	private static ModrinthAPI modrinth(String environment) {
		return new ModrinthAPI("mod", null, "https://cdn.example/a.jar", "1.0.0", "a.jar", 1L, "release", SHA1, environment);
	}

	private static CurseForgeAPI curseForge(boolean clientTag, boolean serverTag) {
		return new CurseForgeAPI(null, "https://cdn.example/a.jar", "1.0.0", "a.jar", "1", "release", "12345", SHA1, 7, "https://www.curseforge.com/minecraft/mc-mods/a-mod", clientTag, serverTag);
	}
}
