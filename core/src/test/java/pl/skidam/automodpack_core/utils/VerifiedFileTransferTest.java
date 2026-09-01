package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

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
	void projectionHardlinkRefreshesNamedSourceGitStat() throws Exception {
		Path object = Files.writeString(tempDir.resolve("object"), "named-bytes", StandardCharsets.UTF_8);
		Path projection = tempDir.resolve("active/video.mp4");
		String hash = HashUtils.getHash(object);
		long size = Files.size(object);
		try (FileMetadataCache cache = FileMetadataCache.open(tempDir.resolve("file-metadata"))) {
			cache.overwriteCache(object, hash);
			assertTrue(VerifiedFileTransfer.linkAtomic(object, projection, size, hash, cache));
			assertTrue(Files.isSameFile(object, projection));
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
