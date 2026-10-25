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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.utils.PlatformUtils;

class FileCacheTest {
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

		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("file-cache"))) {
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
		Path cacheDirectory = temporaryDirectory.resolve("file-cache");
		String expectedHash;

		try (FileCache cache = FileCache.open(cacheDirectory)) {
			expectedHash = cache.getOrComputeHash(file);
		}
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			assertEquals(expectedHash, cache.getOrComputeHash(file));
		}
	}

	@Test
	void windowsNativeStatIsAbsentOrComplete() throws Exception {
		Path file = temporaryDirectory.resolve("file.bin");
		Files.writeString(file, "native-stat", StandardCharsets.UTF_8);

		// The one-stat seam works everywhere: fused unix read here, the native stat on Windows.
		FileCache.StatSnapshot fused = FileCache.statSnapshot(file);
		assertNotNull(fused);
		assertTrue(fused.isTrackedRegularFile());
		assertEquals(Files.size(file), fused.size());
		assertEquals(Files.getLastModifiedTime(file).toMillis(), fused.lastModifiedTime().toMillis());

		WindowsFileStat.Snapshot snapshot = WindowsFileStat.read(file);
		if (PlatformUtils.operatingSystem() != PlatformUtils.OperatingSystem.WINDOWS) {
			assertNull(snapshot);
			return;
		}
		if (!PlatformUtils.isX8664()) return;
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

		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("file-cache"))) {
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

		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("file-cache"))) {
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

		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("file-cache"))) {
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
		Path cacheDirectory = temporaryDirectory.resolve("file-cache");
		String expectedHash;

		try (FileCache cache = FileCache.open(cacheDirectory)) {
			expectedHash = cache.getOrComputeHash(file);
		}
		Path record;
		try (var records = Files.walk(cacheDirectory)) {
			record = records.filter(path -> path.getFileName().toString().endsWith(".json")).findFirst().orElseThrow();
		}
		FileTime sentinel = FileTime.from(Instant.ofEpochSecond(1_600_000_000L));
		Files.setLastModifiedTime(record, sentinel);
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			assertEquals(expectedHash, cache.getOrComputeHash(file));
			assertEquals(sentinel, Files.getLastModifiedTime(record));
		}
	}

	@Test
	void murmurIsComputedOnceAndReusedForAnUnchangedFile() throws Exception {
		Path file = temporaryDirectory.resolve("pack.zip");
		Files.writeString(file, "resource pack", StandardCharsets.UTF_8);
		Path cacheDirectory = temporaryDirectory.resolve("file-cache");
		String murmur;
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			cache.getOrComputeHash(file);
			murmur = cache.getOrComputeMurmur(file);
			assertEquals(murmur, cache.getOrComputeMurmur(file));
		}
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			assertEquals(murmur, cache.getOrComputeMurmur(file));
		}
	}

	@Test
	void namedObjectTripwireTrustsSizeAndRejectsDisturbedFingerprint() throws Exception {
		Path object = temporaryDirectory.resolve("object.bin");
		Files.writeString(object, "named-bytes", StandardCharsets.UTF_8);
		String sha1;
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("file-cache"))) {
			sha1 = cache.getOrComputeHash(object);
			assertTrue(cache.matchesImmutable(object, Files.size(object), sha1));
			Files.writeString(object, "other-bytes", StandardCharsets.UTF_8);
			assertFalse(cache.matchesImmutable(object, Files.size(object), sha1));
		}
	}

	@Test
	void namedObjectTripwirePublishesTheObservedHashWhenTheAnswerIsFalse() throws Exception {
		Path object = temporaryDirectory.resolve("object.bin");
		Files.writeString(object, "named-bytes", StandardCharsets.UTF_8);
		Path cacheDirectory = temporaryDirectory.resolve("file-cache");
		String expectedSha1;
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			expectedSha1 = cache.getOrComputeHash(object);
		}
		Files.writeString(object, "other-bytes", StandardCharsets.UTF_8);
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			assertFalse(cache.matchesImmutable(object, Files.size(object), expectedSha1));
			// The false answer was paid for with a full read, so it must seed the tripwire instead of being dropped.
			try (var records = Files.walk(cacheDirectory)) {
				assertTrue(records.filter(path -> path.getFileName().toString().endsWith(".json")).findAny().isPresent());
			}
			String observedSha1 = cache.getOrComputeHash(object);
			assertFalse(cache.matchesImmutable(object, Files.size(object), expectedSha1));
			assertTrue(cache.matchesImmutable(object, Files.size(object), observedSha1));
			Files.writeString(object, "named-bytes", StandardCharsets.UTF_8);
			assertTrue(cache.matchesImmutable(object, Files.size(object), expectedSha1));
		}
	}

	@Test
	void namedObjectTripwireRehashesARacyRecordEvenWhenTheRewriteKeptEveryStatField() throws Exception {
		Path object = temporaryDirectory.resolve("object.bin");
		Files.writeString(object, "named-bytes", StandardCharsets.UTF_8);
		// A modification time not older than the proof stands in for a rewrite landing in the same timestamp tick.
		FileTime racy = FileTime.from(Instant.now().plusSeconds(3600));
		Files.setLastModifiedTime(object, racy);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("file-cache"))) {
			String sha1 = cache.getOrComputeHash(object);
			assertTrue(cache.matchesImmutable(object, Files.size(object), sha1));
			Files.writeString(object, "other-bytes", StandardCharsets.UTF_8);
			Files.setLastModifiedTime(object, racy);
			assertFalse(cache.matchesImmutable(object, Files.size(object), sha1));
		}
	}

	@Test
	void reproofOfARacyRecordPersistsTheSettledStampSoARestartTrustsIt() throws Exception {
		Path object = temporaryDirectory.resolve("object.bin");
		Files.writeString(object, "named-bytes", StandardCharsets.UTF_8);
		Files.setLastModifiedTime(object, FileTime.from(Instant.ofEpochSecond(1_700_000_000L)));
		String pathKey = object.toAbsolutePath().normalize().toString();
		Path provingDirectory = temporaryDirectory.resolve("proving-cache");
		String sha1;
		Path provedRecord;
		try (FileCache cache = FileCache.open(provingDirectory)) {
			sha1 = cache.getOrComputeHash(object);
			provedRecord = cache.recordPath(pathKey);
		}
		// The record a restart finds on disk after a proof in the object's own mtime tick (a download finishing as it was
		// hashed), built without sleeping. A fresh cache directory has no warm records, so it reads this one like a restart.
		Path cacheDirectory = temporaryDirectory.resolve("file-cache");
		Path record = cacheDirectory.resolve(provingDirectory.relativize(provedRecord));
		JsonObject json = JsonParser.parseString(Files.readString(provedRecord, StandardCharsets.UTF_8)).getAsJsonObject();
		json.addProperty("validatedAtNanos", json.get("lastModifiedNanos").getAsLong());
		Files.createDirectories(record.getParent());
		Files.writeString(record, json.toString(), StandardCharsets.UTF_8);
		assertTrue(persistedRecordIsRacy(record));
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			assertEquals(record, cache.recordPath(pathKey));
			assertTrue(cache.matchesImmutable(object, Files.size(object), sha1));
		}
		assertFalse(persistedRecordIsRacy(record), "the settled re-proof must reach disk, or every restart rehashes the object again");
	}

	@Test
	void clockReadingsRewriteOnePersistentFileBesideTheRecordShards() throws Exception {
		Path cacheDirectory = temporaryDirectory.resolve("file-cache");
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			for (int i = 0; i < 5; i++) {
				Path file = temporaryDirectory.resolve("file-" + i + ".bin");
				Files.writeString(file, "bytes-" + i, StandardCharsets.UTF_8);
				Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(3600)));
				assertNotNull(cache.getOrComputeHash(file));
			}
		}
		try (var entries = Files.list(cacheDirectory)) {
			// Everything but the clock is a two-character record shard directory.
			assertTrue(entries.allMatch(entry -> entry.getFileName().toString().equals(FileCache.CLOCK_FILE) ? Files.isRegularFile(entry) : Files.isDirectory(entry) && entry.getFileName().toString().length() == 2));
		}
		assertTrue(Files.isRegularFile(cacheDirectory.resolve(FileCache.CLOCK_FILE)));
	}

	@Test
	void anUnwritableClockFallsBackToTheWallClock() throws Exception {
		Path cacheDirectory = temporaryDirectory.resolve("file-cache");
		Files.createDirectories(cacheDirectory.resolve(FileCache.CLOCK_FILE));
		Path file = temporaryDirectory.resolve("file.bin");
		Files.writeString(file, "bytes", StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(1_700_000_000L)));
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			String sha1 = cache.getOrComputeHash(file);
			assertNotNull(sha1);
			assertTrue(cache.matchesImmutable(file, Files.size(file), sha1));
			assertFalse(persistedRecordIsRacy(cache.recordPath(file.toAbsolutePath().normalize().toString())));
		}
	}

	@Test
	void anUnwritableClockKeepsTheLastRecordsClockReading() throws Exception {
		Path cacheDirectory = temporaryDirectory.resolve("file-cache");
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			Path first = temporaryDirectory.resolve("first.bin");
			Files.writeString(first, "first", StandardCharsets.UTF_8);
			Files.setLastModifiedTime(first, FileTime.from(Instant.now().plusSeconds(3600)));
			cache.getOrComputeHash(first);
			long reading = validatedAt(cache.recordPath(first.toAbsolutePath().normalize().toString()));
			// A concurrent open refused (a Windows sharing violation) stands in as a clock that cannot be rewritten.
			Files.delete(cacheDirectory.resolve(FileCache.CLOCK_FILE));
			Files.createDirectories(cacheDirectory.resolve(FileCache.CLOCK_FILE));
			Path second = temporaryDirectory.resolve("second.bin");
			Files.writeString(second, "second", StandardCharsets.UTF_8);
			Files.setLastModifiedTime(second, FileTime.from(Instant.now().plusSeconds(3600)));
			cache.getOrComputeHash(second);
			assertEquals(reading, validatedAt(cache.recordPath(second.toAbsolutePath().normalize().toString())), "an earlier records-clock reading is the conservative stamp");
		}
	}

	@Test
	void aReproofThatIsStillRacyKeepsThePersistedRecord() throws Exception {
		Path object = temporaryDirectory.resolve("object.bin");
		Files.writeString(object, "named-bytes", StandardCharsets.UTF_8);
		// A modification time ahead of the records clock (a skewed share, an archive's future stamp) stays racy for every proof.
		Files.setLastModifiedTime(object, FileTime.from(Instant.now().plusSeconds(3600)));
		Path cacheDirectory = temporaryDirectory.resolve("file-cache");
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			String sha1 = cache.getOrComputeHash(object);
			Path record = cache.recordPath(object.toAbsolutePath().normalize().toString());
			Object key = Files.readAttributes(record, BasicFileAttributes.class).fileKey();
			assumeTrue(key != null, "The filesystem does not expose file keys");
			for (int i = 0; i < 3; i++) assertTrue(cache.matchesImmutable(object, Files.size(object), sha1));
			assertEquals(key, Files.readAttributes(record, BasicFileAttributes.class).fileKey(), "a re-proof that cannot settle must not rewrite the record on every ask");
		}
	}

	private static long validatedAt(Path record) throws IOException {
		return JsonParser.parseString(Files.readString(record, StandardCharsets.UTF_8)).getAsJsonObject().get("validatedAtNanos").getAsLong();
	}

	private static boolean persistedRecordIsRacy(Path record) throws IOException {
		JsonObject json = JsonParser.parseString(Files.readString(record, StandardCharsets.UTF_8)).getAsJsonObject();
		return json.get("lastModifiedNanos").getAsLong() >= json.get("validatedAtNanos").getAsLong();
	}

	@Test
	void namedObjectTripwireTrustsChangeTimeBumpsFromOurOwnHardlinks() throws Exception {
		Path object = temporaryDirectory.resolve("object.bin");
		Files.writeString(object, "named-bytes", StandardCharsets.UTF_8);
		// A settled object, so the only disturbance left to answer is the link's ctime bump; a racy record is re-persisted by design.
		Files.setLastModifiedTime(object, FileTime.from(Instant.ofEpochSecond(1_700_000_000L)));
		Path cacheDirectory = temporaryDirectory.resolve("file-cache");
		try (FileCache cache = FileCache.open(cacheDirectory)) {
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
		FileCache.FileFingerprint racy = new FileCache.FileFingerprint(futureModified, 1L, 2L, 4L, "key");
		FileCache.CachedFile record = new FileCache.CachedFile("path", "hash", futureModified, 1L, 2L, 4L, "key", validatedAt, null);

		assertTrue(FileCache.statsMatch(record, racy));
		assertFalse(FileCache.isCacheValid(record, racy));
	}

	@Test
	void worktreeCacheTrustsMtimeOlderThanTheRecord() {
		long validatedAt = 1_700_000_000L * 1_000_000_000L;
		long olderModified = validatedAt - 1;
		FileCache.FileFingerprint fingerprint = new FileCache.FileFingerprint(olderModified, 1L, Long.MIN_VALUE, 4L, "key");
		FileCache.CachedFile record = new FileCache.CachedFile("path", "hash", olderModified, 1L, Long.MIN_VALUE, 4L, "key", validatedAt, null);

		assertTrue(FileCache.statsMatch(record, fingerprint));
		assertTrue(FileCache.isCacheValid(record, fingerprint));
	}

	@Test
	void immutableTripwireUsesStatMatchEvenWhenWorktreeCacheIsRacy() {
		long validatedAt = 1_700_000_000L * 1_000_000_000L;
		FileCache.FileFingerprint racy = new FileCache.FileFingerprint(validatedAt, 1L, Long.MIN_VALUE, 4L, "key");
		FileCache.CachedFile record = new FileCache.CachedFile("path", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", validatedAt, 1L, Long.MIN_VALUE, 4L, "key", validatedAt, null);

		assertTrue(FileCache.statsMatch(record, racy));
		assertFalse(FileCache.isCacheValid(record, racy));
	}
}
