package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HashUtilsTest {
	@TempDir
	Path tempDir;

	@Test
	void hashesFileWithSha1() throws IOException {
		Path file = Files.writeString(tempDir.resolve("test-hash.txt"), "test content 2137!");
		assertEquals("16883d77e42fcb574c70e31cda49b3f955a48be8", HashUtils.getHash(file));
	}

	@Test
	void sha1HelpersShareCanonicalEncodingAndValidationPolicy() {
		byte[] bytes = "test content 2137!".getBytes(StandardCharsets.UTF_8);
		assertEquals("16883d77e42fcb574c70e31cda49b3f955a48be8", HashUtils.sha1(bytes));
		assertEquals(HashUtils.sha1(bytes), HashUtils.sha1("test content 2137!"));
		assertTrue(HashUtils.isSha1("16883d77e42fcb574c70e31cda49b3f955a48be8"));
		assertTrue(HashUtils.isSha1("16883D77E42FCB574C70E31CDA49B3F955A48BE8"));
		assertTrue(HashUtils.isCanonicalSha1("16883d77e42fcb574c70e31cda49b3f955a48be8"));
		assertFalse(HashUtils.isCanonicalSha1("16883D77E42FCB574C70E31CDA49B3F955A48BE8"));
		assertFalse(HashUtils.isSha1("not-a-sha1"));
	}

	@Test
	void incrementalSha1HelperMatchesByteArrayHash() {
		var digest = HashUtils.newSha1Digest();
		digest.update("test ".getBytes(StandardCharsets.UTF_8));
		digest.update("content 2137!".getBytes(StandardCharsets.UTF_8));
		assertEquals(HashUtils.sha1("test content 2137!"), HexFormat.of().formatHex(digest.digest()));
	}

	@Test
	void curseforgeHashMatchesKnownValue() throws IOException {
		Path file = Files.writeString(tempDir.resolve("murmur-test.txt"), "test content 2137!");
		assertEquals("3151456706", HashUtils.getCurseforgeMurmurHash(file));
	}

	@Test
	void curseforgeHashIgnoresWhitespace() throws IOException {
		Path cleanFile = Files.writeString(tempDir.resolve("clean.txt"), "test");
		Path messyFile = Files.writeString(tempDir.resolve("messy.txt"), " t\te\ns\rt ");
		assertEquals(HashUtils.getCurseforgeMurmurHash(cleanFile), HashUtils.getCurseforgeMurmurHash(messyFile));
		assertEquals("2667173943", HashUtils.getCurseforgeMurmurHash(messyFile));
	}

	@Test
	void missingFileHasNoHash() {
		assertNull(HashUtils.getHash(tempDir.resolve("does-not-exist.txt")));
	}
}
