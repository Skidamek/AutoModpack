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
	void onlyDedicatedServerOnlyKeepsTheJarOffClients() throws IOException {
		assertEquals("modrinth environment dedicated_server_only", reason(modrinth("Dedicated_Server_Only")));
		// A server_only file stays usable in Singleplayer, so a client can carry it.
		assertNull(reason(modrinth("server_only")));
	}

	@Test
	void aModrinthEnvironmentDecidesEvenWhenItDecidesNothing() throws IOException {
		assertNull(reason(modrinth("unknown")));
		assertNull(reason(modrinth("client_and_server")));
		assertNull(reason(modrinth("server_only_client_optional")));
	}

	/** Records come back under the normalized digest, so a caller may ask in any case. */
	@Test
	void recordsAreKeyedByTheNormalizedDigest() throws IOException {
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory.resolve("platform-cache"))) {
			cache.putModrinth(SHA1, modrinth("dedicated_server_only"), null);

			assertEquals("modrinth environment dedicated_server_only", cache.getAll(List.of(SHA1.toUpperCase(Locale.ROOT))).get(SHA1).clientExclusionReason());
		}
	}

	private String reason(ModrinthAPI modrinth) throws IOException {
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory.resolve("platform-cache"))) {
			cache.putModrinth(SHA1, modrinth, null);
			PlatformCache.Record record = cache.getAll(List.of(SHA1)).get(SHA1);
			return record == null ? null : record.clientExclusionReason();
		}
	}

	private static ModrinthAPI modrinth(String environment) {
		return new ModrinthAPI("mod", null, "https://cdn.example/a.jar", "1.0.0", "a.jar", 1L, "release", SHA1, environment);
	}
}
