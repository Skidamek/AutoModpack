package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.cache.FileCache;

class VerifiedFileTransferTest {
	@TempDir
	Path tempDir;

	@Test
	void atomicCopySkipsCorrectTargetAndReplacesCorruptTarget() throws IOException {
		Path source = Files.writeString(tempDir.resolve("store-object"), "verified content", StandardCharsets.UTF_8);
		Path target = tempDir.resolve("mods/example.jar");
		String hash = HashUtils.getHash(source);
		long size = Files.size(source);

		assertTrue(VerifiedFileTransfer.copyAtomic(source, target, size, hash));
		assertFalse(Files.isSameFile(source, target));
		assertFalse(VerifiedFileTransfer.copyAtomic(source, target, size, hash));

		Files.writeString(target, "corrupt", StandardCharsets.UTF_8);
		assertTrue(VerifiedFileTransfer.copyAtomic(source, target, size, hash));
		assertTrue(FileIntegrity.matches(target, size, hash));
	}

	@Test
	void createOnlyCopyNeverReplacesDifferentTargetBytes() throws IOException {
		Path source = Files.writeString(tempDir.resolve("create-only-source"), "verified content", StandardCharsets.UTF_8);
		Path target = tempDir.resolve("mods/create-only.jar");
		String hash = HashUtils.getHash(source);
		long size = Files.size(source);

		assertTrue(VerifiedFileTransfer.copyCreateOnly(source, target, size, hash));
		assertFalse(Files.isSameFile(source, target));
		assertFalse(VerifiedFileTransfer.copyCreateOnly(source, target, size, hash));
		Files.writeString(target, "different", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> VerifiedFileTransfer.copyCreateOnly(source, target, size, hash));
		assertEquals("different", Files.readString(target, StandardCharsets.UTF_8));
	}

	@Test
	void immutableCopyProtectsNewAndReplacedTargets() throws IOException {
		Path source = Files.writeString(tempDir.resolve("immutable-source"), "verified content", StandardCharsets.UTF_8);
		Path target = tempDir.resolve("objects/example");
		String hash = HashUtils.getHash(source);
		long size = Files.size(source);

		assertTrue(VerifiedFileTransfer.copyAtomicImmutable(source, target, size, hash));
		assertTrue(ImmutableFiles.isProtected(target));
		ImmutableFiles.protect(target);
		assertTrue(ImmutableFiles.isProtected(target));
		ImmutableFiles.allowOwnerWrite(target);
		Files.writeString(target, "corrupt", StandardCharsets.UTF_8);
		assertTrue(VerifiedFileTransfer.copyAtomicImmutable(source, target, size, hash));
		assertTrue(FileIntegrity.matches(target, size, hash));
		assertTrue(ImmutableFiles.isProtected(target));
	}

	@Test
	void crossFilesystemPromotionConsumesSourceAfterSuccessfulPublish() throws IOException {
		Path sourceDirectory = differentFileStoreDirectory(tempDir);
		Assumptions.assumeTrue(sourceDirectory != null, "requires a writable second filesystem");
		Path source = sourceDirectory.resolve("download");
		try {
			Files.writeString(source, "verified content", StandardCharsets.UTF_8);
			Path target = tempDir.resolve("objects/cross-filesystem");
			String hash = HashUtils.getHash(source);
			long size = Files.size(source);

			VerifiedFileTransfer.promoteAtomic(source, target, size, hash);

			assertFalse(Files.exists(source));
			assertTrue(FileIntegrity.matches(target, size, hash));
		} finally {
			ImmutableFiles.deleteIfExists(source);
			if (sourceDirectory != null) Files.deleteIfExists(sourceDirectory);
		}
	}

	@Test
	void hashedPromotionRejectsAWrongHashOrSizeAndPublishesOnlyMatchingBytes() throws IOException {
		Path source = Files.writeString(tempDir.resolve("hashed-source"), "verified content", StandardCharsets.UTF_8);
		Path target = tempDir.resolve("objects/hashed-target");
		String hash = HashUtils.getHash(source);
		long size = Files.size(source);

		assertThrows(VerifiedFileTransfer.VerificationMismatchException.class,
				() -> VerifiedFileTransfer.promoteHashed(source, target, size, hash, "0000000000000000000000000000000000000000", null));
		assertThrows(VerifiedFileTransfer.VerificationMismatchException.class, () -> VerifiedFileTransfer.promoteHashed(source, target, size + 1, hash, hash, null));
		assertThrows(VerifiedFileTransfer.VerificationMismatchException.class, () -> VerifiedFileTransfer.promoteHashed(source, target, size, hash, null, null));
		assertFalse(Files.exists(target));
		assertTrue(Files.exists(source));

		try (FileCache cache = FileCache.open(tempDir.resolve("file-cache"))) {
			VerifiedFileTransfer.promoteHashed(source, target, size, hash, hash.toUpperCase(), cache);
			assertFalse(Files.exists(source));
			assertTrue(FileIntegrity.matches(target, size, hash));
			assertTrue(ImmutableFiles.isProtected(target));
			assertTrue(cache.matchesImmutable(target, size, hash));
		}
	}

	@Test
	void sealedPublicationRejectsAnUnsealedSourceAndConsumesASealedOne() throws IOException {
		Path source = Files.writeString(tempDir.resolve("sealed-source"), "verified content", StandardCharsets.UTF_8);
		Path target = tempDir.resolve("objects/sealed-target");
		Path control = Files.writeString(tempDir.resolve("sealed-control"), "control", StandardCharsets.UTF_8);
		ImmutableFiles.protect(control);
		if (!ImmutableFiles.isProtected(control)) {
			ImmutableFiles.unprotect(control);
			Assumptions.assumeTrue(false, "filesystem cannot enforce read-only files");
			return;
		}
		try {
			String hash = HashUtils.getHash(source);
			long size = Files.size(source);
			assertThrows(IOException.class, () -> VerifiedFileTransfer.publishSealed(source, target, size, hash));
			assertFalse(Files.exists(target));
			assertTrue(Files.exists(source));

			ImmutableFiles.protectAndForce(source);
			assertTrue(ImmutableFiles.isSealed(source));
			Path parent = VerifiedFileTransfer.publishSealed(source, target, size, hash);
			assertEquals(target.toAbsolutePath().normalize().getParent(), parent);
			assertFalse(Files.exists(source));
			assertTrue(FileIntegrity.matches(target, size, hash));
			assertTrue(ImmutableFiles.isProtected(target));
		} finally {
			ImmutableFiles.unprotect(control);
		}
	}

	@Test
	void hashedCrossFilesystemPromotionStillVerifiesTheCopy() throws IOException {
		Path sourceDirectory = differentFileStoreDirectory(tempDir);
		Assumptions.assumeTrue(sourceDirectory != null, "requires a writable second filesystem");
		Path source = sourceDirectory.resolve("hashed-download");
		try {
			Files.writeString(source, "verified content", StandardCharsets.UTF_8);
			Path target = tempDir.resolve("objects/hashed-cross-filesystem");
			String hash = HashUtils.getHash(source);
			long size = Files.size(source);

			VerifiedFileTransfer.promoteHashed(source, target, size, hash, hash, null);

			assertFalse(Files.exists(source));
			assertTrue(FileIntegrity.matches(target, size, hash));
		} finally {
			ImmutableFiles.deleteIfExists(source);
			if (sourceDirectory != null) Files.deleteIfExists(sourceDirectory);
		}
	}

	@Test
	void projectionHardlinkRefreshesNamedSourceGitStat() throws Exception {
		Path object = Files.writeString(tempDir.resolve("object"), "named-bytes", StandardCharsets.UTF_8);
		Path projection = tempDir.resolve("active/video.mp4");
		String hash = HashUtils.getHash(object);
		long size = Files.size(object);
		try (FileCache cache = FileCache.open(tempDir.resolve("file-cache"))) {
			cache.overwriteCache(object, hash);
			assertTrue(VerifiedFileTransfer.linkAtomic(object, projection, size, hash, cache));
			assertTrue(Files.isSameFile(object, projection));
			assertTrue(FileIntegrity.matchesNamed(object, size, hash, cache));
			assertTrue(FileIntegrity.matchesObject(projection, object, size, hash, cache));
			assertFalse(VerifiedFileTransfer.linkAtomic(object, projection, size, hash, cache), "an already matching projection is left alone");
		}
	}

	@Test
	void directLinkReportsTheValidatedSourceFingerprintForItsCacheRecord() throws Exception {
		Path object = Files.writeString(tempDir.resolve("object"), "direct-fingerprint", StandardCharsets.UTF_8);
		Path mods = Files.createDirectories(tempDir.resolve("mods"));
		Path projection = mods.resolve("file.jar");
		String hash = HashUtils.getHash(object);
		long size = Files.size(object);
		ImmutableFiles.protect(object);
		try (FileCache cache = FileCache.open(tempDir.resolve("file-cache"))) {
			ValidatedSources sources = new ValidatedSources(cache);
			FileCache.FileFingerprint before = FileCache.statSnapshot(object).fingerprint();

			VerifiedFileTransfer.Publication publication = VerifiedFileTransfer.linkAtomicResult(object, projection, size, hash, cache, sources, true);

			assertTrue(publication.changed());
			assertNotNull(publication.fingerprint());
			assertEquals(before.size(), publication.fingerprint().size());
			assertEquals(before.lastModifiedNanos(), publication.fingerprint().lastModifiedNanos());
			assertEquals(before.fileKey(), publication.fingerprint().fileKey());
			cache.overwriteCacheBatch(Map.of(projection, hash), Map.of(projection, publication.fingerprint()), Set.of(projection));
			assertTrue(cache.matchesImmutable(projection, size, hash), "a record seeded from the link's source fingerprint answers the published name");
		}
	}

	@Test
	void aTargetTheCallerBelievedAbsentIsStillReplaced() throws Exception {
		Path object = Files.writeString(tempDir.resolve("object"), "published-bytes", StandardCharsets.UTF_8);
		Path mods = Files.createDirectories(tempDir.resolve("mods"));
		Path projection = Files.writeString(mods.resolve("file.jar"), "appeared-anyway", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(object);
		long size = Files.size(object);
		try (FileCache cache = FileCache.open(tempDir.resolve("file-cache"))) {
			VerifiedFileTransfer.Publication publication = VerifiedFileTransfer.linkAtomicResult(object, projection, size, hash, cache, new ValidatedSources(cache), true);

			assertTrue(publication.changed());
			assertTrue(Files.isSameFile(object, projection), "the unobserved target converges to the verified object");
			assertEquals(List.of("file.jar"), names(mods));
		}
	}

	@Test
	void projectionLinkPublishesDirectlyAndLeavesNoTemporary() throws Exception {
		Path object = Files.writeString(tempDir.resolve("object"), "direct-bytes", StandardCharsets.UTF_8);
		Path mods = Files.createDirectories(tempDir.resolve("mods"));
		Path projection = mods.resolve("file.jar");
		String hash = HashUtils.getHash(object);
		long size = Files.size(object);
		ImmutableFiles.protect(object);
		try (FileCache cache = FileCache.open(tempDir.resolve("file-cache"))) {
			cache.overwriteCache(object, hash);
			assertTrue(VerifiedFileTransfer.linkAtomic(object, projection, size, hash, cache));
			assertTrue(Files.isSameFile(object, projection));
			assertTrue(ImmutableFiles.isProtected(projection), "the direct link keeps the sealed inode policy");
			assertEquals(List.of("file.jar"), names(mods), "the direct publication creates no temporary to clean up");
		}
	}

	@Test
	void projectionLinkReplacesAStaleTargetWithoutLeavingATemporary() throws Exception {
		Path object = Files.writeString(tempDir.resolve("object"), "replacement-bytes", StandardCharsets.UTF_8);
		Path mods = Files.createDirectories(tempDir.resolve("mods"));
		Path projection = Files.writeString(mods.resolve("file.jar"), "stale-bytes", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(object);
		long size = Files.size(object);
		try (FileCache cache = FileCache.open(tempDir.resolve("file-cache"))) {
			cache.overwriteCache(object, hash);
			assertTrue(VerifiedFileTransfer.linkAtomic(object, projection, size, hash, cache));
			assertTrue(Files.isSameFile(object, projection));
			assertEquals(List.of("file.jar"), names(mods), "the replace path moves its temporary instead of probing it afterwards");
		}
	}

	@Test
	void projectionLinkAcrossFileStoresFallsBackToAVerifiedCopy() throws Exception {
		Path objectDirectory = differentFileStoreDirectory(tempDir);
		Assumptions.assumeTrue(objectDirectory != null, "requires a writable second filesystem");
		Path object = objectDirectory.resolve("object");
		try {
			Files.writeString(object, "cross-filesystem-bytes", StandardCharsets.UTF_8);
			Path projection = tempDir.resolve("mods/file.jar");
			String hash = HashUtils.getHash(object);
			long size = Files.size(object);

			assertTrue(VerifiedFileTransfer.linkAtomic(object, projection, size, hash, null));
			assertTrue(FileIntegrity.matches(projection, size, hash));
			assertTrue(Files.exists(object), "the content-addressed object survives an install that had to copy");
		} finally {
			ImmutableFiles.deleteIfExists(object);
		}
	}

	private static List<String> names(Path directory) throws IOException {
		try (var entries = Files.list(directory)) {
			return entries.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	@Test
	void namedSourceCopySurvivesAnExtraHardlinkWithoutRehashingTheObject() throws Exception {
		Path object = Files.writeString(tempDir.resolve("object"), "named-bytes", StandardCharsets.UTF_8);
		Path target = tempDir.resolve("mods/out.jar");
		String hash = HashUtils.getHash(object);
		long size = Files.size(object);
		try (FileCache cache = FileCache.open(tempDir.resolve("file-cache"))) {
			assertTrue(cache.matchesImmutable(object, size, hash));
			try {
				Files.createLink(tempDir.resolve("alias"), object);
			} catch (UnsupportedOperationException | FileSystemException e) {
				Assumptions.assumeTrue(false, "hardlinks unavailable");
				return;
			}
			assertTrue(VerifiedFileTransfer.copyAtomic(object, target, size, hash, cache));
			assertTrue(FileIntegrity.matches(target, size, hash, cache));
			assertTrue(FileIntegrity.matchesNamed(object, size, hash, cache));
		}
	}

	@Test
	void failedPromotionLeavesSourceForCallerCleanup() throws IOException {
		Path source = Files.writeString(tempDir.resolve("failed-promotion-source"), "verified content", StandardCharsets.UTF_8);
		Path targetParent = Files.writeString(tempDir.resolve("not-a-directory"), "not a directory", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(source);
		long size = Files.size(source);

		assertThrows(IOException.class, () -> VerifiedFileTransfer.promoteAtomic(source, targetParent.resolve("target"), size, hash));
		assertTrue(Files.exists(source));
		ImmutableFiles.deleteIfExists(source);
	}

	@Test
	void promotionThrowsItsTypedMismatchAndPublishesOnlyMatchingBytes() throws IOException {
		Path source = Files.writeString(tempDir.resolve("promotion-source"), "verified content", StandardCharsets.UTF_8);
		Path target = tempDir.resolve("objects/promotion-target");
		String hash = HashUtils.getHash(source);
		long size = Files.size(source);

		assertThrows(VerifiedFileTransfer.VerificationMismatchException.class,
				() -> VerifiedFileTransfer.promoteAtomic(source, target, size, "0000000000000000000000000000000000000000"));
		assertFalse(Files.exists(target));
		assertTrue(Files.exists(source));

		VerifiedFileTransfer.promoteAtomic(source, target, size, hash);
		assertTrue(FileIntegrity.matches(target, size, hash));
	}

	@Test
	void aLinkFallbackCopyOfAProtectedSourceComesOutOwnerWritable() throws IOException {
		Path object = Files.writeString(tempDir.resolve("object"), "protected-bytes", StandardCharsets.UTF_8);
		String hash = HashUtils.getHash(object);
		long size = Files.size(object);
		ImmutableFiles.protect(object);
		Path temporary = tempDir.resolve(".object.123.tmp");
		try (FileCache cache = FileCache.open(tempDir.resolve("file-cache"))) {
			cache.overwriteCache(object, hash);
			VerifiedFileTransfer.copyNamedOrHashed(object, temporary, size, hash, cache);
			assertTrue(Files.isWritable(temporary), "the link fallback's copy is reopened for the force sync, so it must come out writable");
			assertTrue(FileIntegrity.matches(temporary, size, hash));
		}
	}

	private static Path differentFileStoreDirectory(Path targetRoot) throws IOException {
		FileStore targetStore = Files.getFileStore(targetRoot);
		for (Path candidate : List.of(Path.of(System.getProperty("user.home")), Path.of("/dev/shm"), Path.of("/tmp"))) {
			if (!Files.isDirectory(candidate) || targetStore.equals(Files.getFileStore(candidate))) continue;
			try {
				return Files.createTempDirectory(candidate, "verified-transfer-test-");
			} catch (IOException ignored) {
				// Try the next writable filesystem.
			}
		}
		return null;
	}
}
