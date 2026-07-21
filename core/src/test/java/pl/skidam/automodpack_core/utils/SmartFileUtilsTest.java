package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmartFileUtilsTest {

	@TempDir
	Path tempDir;

	@Test
	void testSHA1Hash_KnownValue() throws IOException, NoSuchAlgorithmException {
		Path file = tempDir.resolve("test-hash.txt");
		String content = "test content 2137!";
		Files.writeString(file, content);

		String actualHash = HashUtils.getHash(file);

		assertNotNull(actualHash, "Hash should not be null");
		assertEquals("16883d77e42fcb574c70e31cda49b3f955a48be8", actualHash, "getHash should return the correct SHA-1 hash");
	}

	@Test
	void testMurmurHash_KnownValue() throws IOException {
		Path file = tempDir.resolve("murmur-test.txt");
		Files.writeString(file, "test content 2137!");

		String actualHash = HashUtils.getCurseforgeMurmurHash(file);

		assertEquals("3151456706", actualHash, "MurmurHash for 'test' should match known constant");
	}

	@Test
	void testMurmurHash_IgnoresWhitespace() throws IOException {
		Path cleanFile = tempDir.resolve("clean.txt");
		Path messyFile = tempDir.resolve("messy.txt");

		String cleanContent = "test";
		String messyContent = " t\te\ns\rt ";

		Files.writeString(cleanFile, cleanContent);
		Files.writeString(messyFile, messyContent);

		String cleanHash = HashUtils.getCurseforgeMurmurHash(cleanFile);
		String messyHash = HashUtils.getCurseforgeMurmurHash(messyFile);

		assertEquals(cleanHash, messyHash, "Hashes should be identical despite whitespace differences");
		assertEquals("2667173943", messyHash, "Messy file should still hash to the value of 'test'");
	}

	@Test
	void testGetHash_NonExistentFile() {
		Path missingFile = tempDir.resolve("does-not-exist.txt");

		String result = HashUtils.getHash(missingFile);

		assertNull(result, "Should return null for missing file");
	}

	@Test
	void copyBatchWrapsRuntimeFailuresAndPreservesInterruption() throws Exception {
		Path source = Files.writeString(tempDir.resolve("batch-source"), "content");
		Path failedTarget = tempDir.resolve("failed-target");
		SmartFileUtils.CopyBatchException runtimeFailure = assertThrows(SmartFileUtils.CopyBatchException.class,
				() -> SmartFileUtils.copyVerifiedAtomicBatch(List.of(new SmartFileUtils.CopyRequest(source, failedTarget, Files.size(source), null)), 1));
		assertEquals(failedTarget, runtimeFailure.target());
		assertInstanceOf(NullPointerException.class, runtimeFailure.getCause());

		String hash = HashUtils.getHash(source);
		Thread.currentThread().interrupt();
		try {
			assertThrows(IOException.class, () -> SmartFileUtils.copyVerifiedAtomicBatch(
					List.of(new SmartFileUtils.CopyRequest(source, tempDir.resolve("interrupted-target"), Files.size(source), hash)), 1));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void verifiedAtomicCopySkipsCorrectTargetAndReplacesCorruptTarget() throws IOException {
		Path source = tempDir.resolve("store-object");
		Path target = tempDir.resolve("mods/example.jar");
		Files.writeString(source, "verified content");
		String hash = HashUtils.getHash(source);
		long size = Files.size(source);

		assertTrue(SmartFileUtils.copyVerifiedAtomic(source, target, size, hash));
		assertFalse(Files.isSameFile(source, target));
		assertFalse(SmartFileUtils.copyVerifiedAtomic(source, target, size, hash));

		Files.writeString(target, "corrupt");
		assertTrue(SmartFileUtils.copyVerifiedAtomic(source, target, size, hash));
		assertTrue(SmartFileUtils.isValidFile(target, size, hash));
	}
}
