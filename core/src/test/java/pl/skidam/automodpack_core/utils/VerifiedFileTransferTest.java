package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedFileTransferTest {
	@TempDir
	Path tempDir;

	@Test
	void atomicCopySkipsCorrectTargetAndReplacesCorruptTarget() throws IOException {
		Path source = Files.writeString(tempDir.resolve("store-object"), "verified content");
		Path target = tempDir.resolve("mods/example.jar");
		String hash = HashUtils.getHash(source);
		long size = Files.size(source);

		assertTrue(VerifiedFileTransfer.copyAtomic(source, target, size, hash));
		assertFalse(Files.isSameFile(source, target));
		assertFalse(VerifiedFileTransfer.copyAtomic(source, target, size, hash));

		Files.writeString(target, "corrupt");
		assertTrue(VerifiedFileTransfer.copyAtomic(source, target, size, hash));
		assertTrue(FileIntegrity.matches(target, size, hash));
	}

	@Test
	void createOnlyCopyNeverReplacesDifferentTargetBytes() throws IOException {
		Path source = Files.writeString(tempDir.resolve("create-only-source"), "verified content");
		Path target = tempDir.resolve("mods/create-only.jar");
		String hash = HashUtils.getHash(source);
		long size = Files.size(source);

		assertTrue(VerifiedFileTransfer.copyCreateOnly(source, target, size, hash));
		assertFalse(VerifiedFileTransfer.copyCreateOnly(source, target, size, hash));
		Files.writeString(target, "different");
		assertThrows(IOException.class, () -> VerifiedFileTransfer.copyCreateOnly(source, target, size, hash));
		assertEquals("different", Files.readString(target));
	}
}
