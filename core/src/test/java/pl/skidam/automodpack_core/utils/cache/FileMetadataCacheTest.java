package pl.skidam.automodpack_core.utils.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.HashUtils;

class FileMetadataCacheTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void detectsContentChangesBelowMillisecondTimestampPrecision() throws Exception {
		Path file = temporaryDirectory.resolve("file.bin");
		Files.writeString(file, "one", StandardCharsets.UTF_8);
		FileTime firstTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 123_000_000L));
		FileTime secondTime = FileTime.from(firstTime.toInstant().plusNanos(999_999L));
		Files.setLastModifiedTime(file, firstTime);
		FileTime storedFirstTime = Files.getLastModifiedTime(file);
		Files.setLastModifiedTime(file, secondTime);
		FileTime storedSecondTime = Files.getLastModifiedTime(file);
		assumeTrue(!storedFirstTime.equals(storedSecondTime), "The filesystem does not expose sub-millisecond timestamps");
		Files.setLastModifiedTime(file, firstTime);

		try (FileMetadataCache cache = FileMetadataCache.open(temporaryDirectory.resolve("file-metadata"))) {
			String firstHash = cache.getOrComputeHash(file);
			Files.writeString(file, "two", StandardCharsets.UTF_8);
			Files.setLastModifiedTime(file, secondTime);

			assertNotEquals(firstHash, cache.getOrComputeHash(file));
		}
	}

	@Test
	void persistsMetadataForAnUnchangedFileAcrossFreshCacheInstance() throws Exception {
		Path file = temporaryDirectory.resolve("file.bin");
		Files.writeString(file, "persisted", StandardCharsets.UTF_8);
		FileTime originalLastModifiedTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L));
		Files.setLastModifiedTime(file, originalLastModifiedTime);
		Path cacheDirectory = temporaryDirectory.resolve("file-metadata");
		String expectedHash;

		try (FileMetadataCache cache = FileMetadataCache.open(cacheDirectory)) {
			expectedHash = cache.getOrComputeHash(file);
		}
		try (FileMetadataCache cache = FileMetadataCache.open(cacheDirectory)) {
			assertEquals(expectedHash, cache.getOrComputeHash(file));
		}
	}

	@Test
	void detectsSameSizeChangeWhenModifiedTimeIsRestoredOnUnix() throws Exception {
		Path file = temporaryDirectory.resolve("file.bin");
		Files.writeString(file, "first", StandardCharsets.UTF_8);
		FileTime originalLastModifiedTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L));
		Files.setLastModifiedTime(file, originalLastModifiedTime);
		Object originalChangeTime;
		try {
			originalChangeTime = Files.getAttribute(file, "unix:ctime");
		} catch (UnsupportedOperationException e) {
			assumeTrue(false, "The filesystem does not expose Unix change time");
			return;
		}

		try (FileMetadataCache cache = FileMetadataCache.open(temporaryDirectory.resolve("file-metadata"))) {
			String firstHash = cache.getOrComputeHash(file);
			Files.writeString(file, "other", StandardCharsets.UTF_8);
			Files.setLastModifiedTime(file, originalLastModifiedTime);
			assumeTrue(!originalChangeTime.equals(Files.getAttribute(file, "unix:ctime")), "The filesystem did not advance change time");

			assertNotEquals(firstHash, cache.getOrComputeHash(file));
		}
	}

	@Test
	void detectsMutationThroughAnotherHardlinkOnUnix() throws Exception {
		Path object = temporaryDirectory.resolve("object");
		Path projection = temporaryDirectory.resolve("projection");
		Files.writeString(object, "first", StandardCharsets.UTF_8);
		try {
			Files.createLink(projection, object);
		} catch (UnsupportedOperationException e) {
			assumeTrue(false, "The filesystem does not support hardlinks");
			return;
		}
		FileTime originalLastModifiedTime = Files.getLastModifiedTime(object);
		Object originalChangeTime;
		try {
			originalChangeTime = Files.getAttribute(object, "unix:ctime");
		} catch (UnsupportedOperationException e) {
			assumeTrue(false, "The filesystem does not expose Unix change time");
			return;
		}

		try (FileMetadataCache cache = FileMetadataCache.open(temporaryDirectory.resolve("file-metadata"))) {
			String originalHash = cache.getOrComputeHash(object);
			Files.writeString(projection, "other", StandardCharsets.UTF_8);
			Files.setLastModifiedTime(projection, originalLastModifiedTime);
			assumeTrue(!originalChangeTime.equals(Files.getAttribute(object, "unix:ctime")), "The filesystem did not advance change time");

			assertNotEquals(originalHash, cache.getOrComputeHash(object));
		}
	}

	@Test
	void trustedHashBypassesAValidLookingPersistedRecord() throws Exception {
		Path file = temporaryDirectory.resolve("file.bin");
		Files.writeString(file, "first", StandardCharsets.UTF_8);
		FileTime originalLastModifiedTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L));
		Files.setLastModifiedTime(file, originalLastModifiedTime);
		Path cacheDirectory = temporaryDirectory.resolve("file-metadata");
		String staleHash;

		try (FileMetadataCache cache = FileMetadataCache.open(cacheDirectory)) {
			staleHash = cache.getOrComputeHash(file);
			Files.writeString(file, "other", StandardCharsets.UTF_8);
			Files.setLastModifiedTime(file, originalLastModifiedTime);
			cache.overwriteCache(file, staleHash);

			assertEquals(staleHash, cache.getOrComputeHash(file));
		}
		try (FileMetadataCache cache = FileMetadataCache.open(cacheDirectory)) {
			assertEquals(HashUtils.getHash(file), cache.getTrustedHash(file));
			assertNotEquals(staleHash, cache.getOrComputeHash(file));
		}
	}
}
