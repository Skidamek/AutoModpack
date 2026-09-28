package pl.skidam.automodpack_core.platforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.DownloadSource;

class FetchManagerTest {
	private static final String SHA1 = "a".repeat(40);
	private static final String MURMUR = "12345";

	@TempDir
	Path temporaryDirectory;

	@Test
	void cancelledLookupIsAnAbortNotACompletedMiss() throws Exception {
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory)) {
			FetchManager manager = new FetchManager(List.of(new FetchManager.FetchData("mods/a.jar", SHA1, null, "mod")), cache);
			manager.fetchAsync();
			manager.cancel();
			InterruptedException abort = assertThrows(InterruptedException.class, manager::fetch);
			assertTrue(abort.getMessage().contains("cancelled"));
			assertTrue(manager.isCancelled());
			assertTrue(Thread.interrupted());
		}
	}

	@Test
	void aCurseForgeListingWithoutADownloadUrlVerifiesWithoutBecomingASource() throws Exception {
		CurseForgeAPI listed = new CurseForgeAPI(null, null, "A 1.2.3", "a.jar", "123", "release", MURMUR, SHA1, 7, "https://www.curseforge.com/minecraft/mc-mods/a-mod");
		FetchManager.Lookups lookups = new FetchManager.Lookups(sha1s -> List.of(), ids -> Map.of(), hashes -> List.of(listed));
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory)) {
			FetchManager manager = new FetchManager(List.of(new FetchManager.FetchData("mods/a.jar", SHA1, MURMUR, "mod")), cache, lookups);
			manager.fetchAsync().join();
			assertTrue(manager.hasListing(SHA1), "the CurseForge hit must verify the file");
			assertTrue(manager.listedOn(SHA1, DownloadSource.Provider.CURSEFORGE));
			assertTrue(manager.sourcesFor(SHA1).isEmpty(), "a listing without a download url is not a download source");
			assertEquals(1, manager.resolvedFiles(), "the file counts as matched even though only the host can serve it");
			PlatformCache.Record record = cache.getAll(List.of(SHA1)).get(SHA1);
			assertTrue(record != null && record.curseforge() != null && record.curseforge().downloadUrl() == null, "the listing must persist for the next lookup");
		}
	}

	@Test
	void aCachedListingVerifiesTheNextLookupWithoutAskingThePlatforms() throws Exception {
		CurseForgeAPI listed = new CurseForgeAPI(null, null, "A 1.2.3", "a.jar", "123", "release", MURMUR, SHA1, 7, "https://www.curseforge.com/minecraft/mc-mods/a-mod");
		FetchManager.Lookups listing = new FetchManager.Lookups(sha1s -> List.of(), ids -> Map.of(), hashes -> List.of(listed));
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory)) {
			new FetchManager(List.of(new FetchManager.FetchData("mods/a.jar", SHA1, MURMUR, "mod")), cache, listing).fetchAsync().join();
			FetchManager.Lookups silent = new FetchManager.Lookups(sha1s -> List.of(), ids -> Map.of(), hashes -> List.of());
			FetchManager second = new FetchManager(List.of(new FetchManager.FetchData("mods/a.jar", SHA1, MURMUR, "mod")), cache, silent);
			second.fetchAsync().join();
			assertTrue(second.hasListing(SHA1), "the persisted listing must verify the next lookup");
			assertTrue(second.sourcesFor(SHA1).isEmpty());
		}
	}

	@Test
	void aModrinthHitIsBothAListingAndASource() throws Exception {
		ModrinthAPI hit = new ModrinthAPI("aaaa1111", null, "http://cdn.example/a.jar", "1.2.3", "a.jar", 123, "release", SHA1);
		FetchManager.Lookups lookups = new FetchManager.Lookups(sha1s -> List.of(hit), ids -> Map.of("aaaa1111", "a-mod"), hashes -> List.of());
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory)) {
			FetchManager manager = new FetchManager(List.of(new FetchManager.FetchData("mods/a.jar", SHA1, MURMUR, "mod")), cache, lookups);
			manager.fetchAsync().join();
			assertTrue(manager.hasListing(SHA1));
			assertTrue(manager.listedOn(SHA1, DownloadSource.Provider.MODRINTH));
			assertEquals(List.of(new DownloadSource("http://cdn.example/a.jar", DownloadSource.Provider.MODRINTH)), manager.sourcesFor(SHA1));
		}
	}
}
