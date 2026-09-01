package pl.skidam.automodpack_core.utils.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.PlatformUtils;

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
	void windowsNativeStatIsAbsentOrComplete() throws Exception {
		Path file = temporaryDirectory.resolve("file.bin");
		Files.writeString(file, "native-stat", StandardCharsets.UTF_8);
		WindowsFileStat.Snapshot snapshot = WindowsFileStat.read(file);
		if (PlatformUtils.operatingSystem() != PlatformUtils.OperatingSystem.WINDOWS) {
			assertNull(snapshot);
			return;
		}
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		if (!arch.equals("amd64") && !arch.equals("x86_64")) return;
		System.err.println("WindowsFileStat: " + WindowsFileStat.loadError());
		System.err.flush();
		assertNotNull(snapshot, WindowsFileStat.loadError());
		assertNotEquals(Long.MIN_VALUE, snapshot.changeTimeNanos());
		assertNotNull(snapshot.fileKey());
		assertTrue(snapshot.fileKey().contains(":"));
	}

	@Test
	void detectsSameSizeChangeWhenModifiedTimeIsRestoredOnWindows() throws Exception {
		Path file = temporaryDirectory.resolve("file.bin");
		Files.writeString(file, "first", StandardCharsets.UTF_8);
		WindowsFileStat.Snapshot before = WindowsFileStat.read(file);
		assumeTrue(before != null, "Windows NTFS stat native is unavailable");
		FileTime originalLastModifiedTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L));
		Files.setLastModifiedTime(file, originalLastModifiedTime);
		before = WindowsFileStat.read(file);
		assumeTrue(before != null, "Windows NTFS stat native is unavailable");

		try (FileMetadataCache cache = FileMetadataCache.open(temporaryDirectory.resolve("file-metadata"))) {
			String firstHash = cache.getOrComputeHash(file);
			Files.writeString(file, "other", StandardCharsets.UTF_8);
			Files.setLastModifiedTime(file, originalLastModifiedTime);
			WindowsFileStat.Snapshot after = WindowsFileStat.read(file);
			assumeTrue(after != null && after.changeTimeNanos() != before.changeTimeNanos(), "The filesystem did not advance NTFS change time");
			assertNotEquals(firstHash, cache.getOrComputeHash(file));
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
	void trustedHashReusesANonRacyPersistedRecord() throws Exception {
		Path file = temporaryDirectory.resolve("file.bin");
		Files.writeString(file, "first", StandardCharsets.UTF_8);
		FileTime originalLastModifiedTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L));
		Files.setLastModifiedTime(file, originalLastModifiedTime);
		Path cacheDirectory = temporaryDirectory.resolve("file-metadata");
		String expectedHash;

		try (FileMetadataCache cache = FileMetadataCache.open(cacheDirectory)) {
			expectedHash = cache.getOrComputeHash(file);
		}
		Path record;
		try (var records = Files.walk(cacheDirectory)) {
			record = records.filter(path -> path.getFileName().toString().endsWith(".json")).findFirst().orElseThrow();
		}
		FileTime sentinel = FileTime.from(Instant.ofEpochSecond(1_600_000_000L));
		Files.setLastModifiedTime(record, sentinel);
		try (FileMetadataCache cache = FileMetadataCache.open(cacheDirectory)) {
			assertEquals(expectedHash, cache.getOrComputeHash(file));
			assertEquals(sentinel, Files.getLastModifiedTime(record));
		}
	}

	@Test
	void murmurIsComputedOnceAndReusedForAnUnchangedFile() throws Exception {
		Path file = temporaryDirectory.resolve("pack.zip");
		Files.writeString(file, "resource pack", StandardCharsets.UTF_8);
		Path cacheDirectory = temporaryDirectory.resolve("file-metadata");
		String murmur;
		try (FileMetadataCache cache = FileMetadataCache.open(cacheDirectory)) {
			cache.getOrComputeHash(file);
			murmur = cache.getOrComputeMurmur(file);
			assertEquals(murmur, cache.getOrComputeMurmur(file));
		}
		try (FileMetadataCache cache = FileMetadataCache.open(cacheDirectory)) {
			assertEquals(murmur, cache.getOrComputeMurmur(file));
		}
	}

	@Test
	void namedObjectTripwireTrustsSizeAndRejectsDisturbedFingerprint() throws Exception {
		Path object = temporaryDirectory.resolve("object.bin");
		Files.writeString(object, "named-bytes", StandardCharsets.UTF_8);
		String sha1;
		try (FileMetadataCache cache = FileMetadataCache.open(temporaryDirectory.resolve("file-metadata"))) {
			sha1 = cache.getOrComputeHash(object);
			assertTrue(cache.matchesImmutable(object, Files.size(object), sha1));
			Files.writeString(object, "other-bytes", StandardCharsets.UTF_8);
			assertFalse(cache.matchesImmutable(object, Files.size(object), sha1));
		}
	}

	@Test
	void namedObjectTripwireTrustsChangeTimeBumpsFromOurOwnHardlinks() throws Exception {
		Path object = temporaryDirectory.resolve("object.bin");
		Files.writeString(object, "named-bytes", StandardCharsets.UTF_8);
		Path cacheDirectory = temporaryDirectory.resolve("file-metadata");
		try (FileMetadataCache cache = FileMetadataCache.open(cacheDirectory)) {
			String sha1 = cache.getOrComputeHash(object);
			assertTrue(cache.matchesImmutable(object, Files.size(object), sha1));
			FileTime recordStamp = recordStamp(cacheDirectory, sha1);
			Files.createLink(temporaryDirectory.resolve("alias.bin"), object);
			assumeTrue(!recordStamp.equals(FileTime.fromMillis(0)), "The filesystem does not expose record timestamps");
			assertTrue(cache.matchesImmutable(object, Files.size(object), sha1));
			// A ctime-only bump must be trusted without a rehash, so the persisted record stays untouched.
			assertEquals(recordStamp, recordStamp(cacheDirectory, sha1));
		}
	}

	private static FileTime recordStamp(Path cacheDirectory, String sha1) throws IOException {
		try (var records = Files.walk(cacheDirectory)) {
			return records.filter(path -> path.getFileName().toString().endsWith(".json")).findFirst().map(path -> {
				try {
					return Files.getLastModifiedTime(path);
				} catch (IOException e) {
					return FileTime.fromMillis(0);
				}
			}).orElse(FileTime.fromMillis(0));
		}
	}

	@Test
	void gitRacyMtimeIsUntrustedForWorktreeEvenWhenChangeTimeMatches() {
		long validatedAt = 1_700_000_000L * 1_000_000_000L;
		long futureModified = validatedAt + 2 * 1_000_000_000L;
		FileMetadataCache.FileFingerprint racy = new FileMetadataCache.FileFingerprint(futureModified, 1L, 2L, 4L, "key");
		FileMetadataCache.CachedFile record = new FileMetadataCache.CachedFile("path", "hash", futureModified, 1L, 2L, 4L, "key", validatedAt);

		assertTrue(FileMetadataCache.statsMatch(record, racy));
		assertFalse(FileMetadataCache.isCacheValid(record, racy));
	}

	@Test
	void worktreeCacheTrustsMtimeOlderThanTheRecord() {
		long validatedAt = 1_700_000_000L * 1_000_000_000L;
		long olderModified = validatedAt - 1;
		FileMetadataCache.FileFingerprint fingerprint = new FileMetadataCache.FileFingerprint(olderModified, 1L, Long.MIN_VALUE, 4L, "key");
		FileMetadataCache.CachedFile record = new FileMetadataCache.CachedFile("path", "hash", olderModified, 1L, Long.MIN_VALUE, 4L, "key", validatedAt);

		assertTrue(FileMetadataCache.statsMatch(record, fingerprint));
		assertTrue(FileMetadataCache.isCacheValid(record, fingerprint));
	}

	@Test
	void immutableTripwireUsesStatMatchEvenWhenWorktreeCacheIsRacy() {
		long validatedAt = 1_700_000_000L * 1_000_000_000L;
		FileMetadataCache.FileFingerprint racy = new FileMetadataCache.FileFingerprint(validatedAt, 1L, Long.MIN_VALUE, 4L, "key");
		FileMetadataCache.CachedFile record = new FileMetadataCache.CachedFile("path", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", validatedAt, 1L, Long.MIN_VALUE, 4L, "key", validatedAt);

		assertTrue(FileMetadataCache.statsMatch(record, racy));
		assertFalse(FileMetadataCache.isCacheValid(record, racy));
	}
}
