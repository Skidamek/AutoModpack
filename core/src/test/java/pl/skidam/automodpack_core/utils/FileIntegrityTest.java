package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

class FileIntegrityTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void cacheBackedMatchReusesTheIdentityHash() throws Exception {
		Path file = Files.writeString(temporaryDirectory.resolve("object.bin"), "expected-bytes", StandardCharsets.UTF_8);
		long size = Files.size(file);
		String hash = HashUtils.getHash(file);

		try (FileMetadataCache cache = FileMetadataCache.open(temporaryDirectory.resolve("file-metadata"))) {
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
}
