package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.cache.FileCache;

class FileIntegrityTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void cacheBackedMatchReusesTheIdentityHash() throws Exception {
		Path file = Files.writeString(temporaryDirectory.resolve("object.bin"), "expected-bytes", StandardCharsets.UTF_8);
		long size = Files.size(file);
		String hash = HashUtils.getHash(file);

		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("file-cache"))) {
			assertTrue(FileIntegrity.matches(file, size, hash, cache));
			assertEquals(hash, FileIntegrity.identityHash(file, cache));
			assertTrue(FileIntegrity.matches(file, size, hash, cache));
			assertFalse(FileIntegrity.matches(file, size + 1, hash, cache));
			assertFalse(FileIntegrity.matches(file, size, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", cache));
		}
	}

	@Test
	void matchWithoutCacheStillReadsCurrentBytes() throws Exception {
		Path file = Files.writeString(temporaryDirectory.resolve("object.bin"), "expected-bytes", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(file);
		assertTrue(FileIntegrity.matches(file, Files.size(file), hash));
		Files.writeString(file, "other-bytes", StandardCharsets.UTF_8);
		assertFalse(FileIntegrity.matches(file, Files.size(file), hash));
	}

	@Test
	void observedHashReusesTheAdvertisedIdentityWhenTheNamedTripwireHolds() throws Exception {
		Path file = Files.writeString(temporaryDirectory.resolve("live.bin"), "pack-bytes", StandardCharsets.UTF_8);
		long size = Files.size(file);
		String hash = HashUtils.getHash(file);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("file-cache"))) {
			assertEquals(hash, FileIntegrity.observedHash(file, size, hash, cache));
			Files.writeString(file, "other-bytes", StandardCharsets.UTF_8);
			assertNotEquals(hash, FileIntegrity.observedHash(file, size, hash, cache));
		}
	}

	@Test
	void publishedObjectTrustsAHardlinkWithoutRehashingAfterCtimeBump() throws Exception {
		Path object = Files.writeString(temporaryDirectory.resolve("object.bin"), "named-bytes", StandardCharsets.UTF_8);
		Path alias = temporaryDirectory.resolve("projection.bin");
		long size = Files.size(object);
		String hash = HashUtils.getHash(object);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("file-cache"))) {
			assertTrue(cache.matchesImmutable(object, size, hash));
			try {
				Files.createLink(alias, object);
			} catch (UnsupportedOperationException | FileSystemException e) {
				Assumptions.assumeTrue(false, "hardlinks unavailable");
				return;
			}
			assertTrue(FileIntegrity.sameInode(alias, object));
			assertTrue(FileIntegrity.matchesObject(alias, object, size, hash, cache));
			assertTrue(FileIntegrity.matchesNamed(object, size, hash, cache));
		}
	}

	@Test
	void publishedCopyStillMatchesWhenTheCanonicalObjectIsGone() throws Exception {
		Path object = Files.writeString(temporaryDirectory.resolve("object.bin"), "named-bytes", StandardCharsets.UTF_8);
		Path copy = Files.writeString(temporaryDirectory.resolve("projection.bin"), "named-bytes", StandardCharsets.UTF_8);
		long size = Files.size(copy);
		String hash = HashUtils.getHash(copy);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("file-cache"))) {
			assertTrue(FileIntegrity.matchesNamed(copy, size, hash, cache));
			Files.delete(object);
			assertTrue(FileIntegrity.matchesObject(copy, object, size, hash, cache));
		}
	}
}
