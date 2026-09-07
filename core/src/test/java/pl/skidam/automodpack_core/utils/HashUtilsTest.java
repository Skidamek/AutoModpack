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
		Path file = Files.writeString(tempDir.resolve("test-hash.txt"), "test content 2137!", StandardCharsets.UTF_8);
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
		assertEquals("16883d77e42fcb574c70e31cda49b3f955a48be8", HashUtils.normalizeSha1("16883D77E42FCB574C70E31CDA49B3F955A48BE8"));
		assertThrows(IllegalArgumentException.class, () -> HashUtils.normalizeSha1("not-a-sha1"));
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
		Path file = Files.writeString(tempDir.resolve("murmur-test.txt"), "test content 2137!", StandardCharsets.UTF_8);
		assertEquals("3151456706", HashUtils.getCurseforgeMurmurHash(file));
	}

	@Test
	void curseforgeHashIgnoresWhitespace() throws IOException {
		Path cleanFile = Files.writeString(tempDir.resolve("clean.txt"), "test", StandardCharsets.UTF_8);
		Path messyFile = Files.writeString(tempDir.resolve("messy.txt"), " t\te\ns\rt ", StandardCharsets.UTF_8);
		assertEquals(HashUtils.getCurseforgeMurmurHash(cleanFile), HashUtils.getCurseforgeMurmurHash(messyFile));
		assertEquals("2667173943", HashUtils.getCurseforgeMurmurHash(messyFile));
	}

	@Test
	void copyAndSha1MatchesFileHash() throws IOException {
		Path file = Files.writeString(tempDir.resolve("source.bin"), "test content 2137!", StandardCharsets.UTF_8);
		Path copy = tempDir.resolve("copy.bin");
		assertEquals(HashUtils.getHash(file), HashUtils.copyAndSha1(file, copy));
		assertEquals(Files.readString(file, StandardCharsets.UTF_8), Files.readString(copy, StandardCharsets.UTF_8));
	}

	@Test
	void missingFileHasNoHash() {
		assertNull(HashUtils.getHash(tempDir.resolve("does-not-exist.txt")));
	}

	@Test
	void curseforgeHashMatchesByteWiseReferenceAcrossPayloads() throws IOException {
		byte[][] payloads = new byte[][]{new byte[0], new byte[]{0x20, 0x09, 0x0A, 0x0D}, new byte[]{1, 2, 3}, new byte[]{1, 0x20, 2, 0x09, 3, 0x0A, 4, 0x0D, 5}, randomBytes(17), randomBytes(64 * 1024 + 13),
				randomBytes(200_000)};
		for (int index = 0; index < payloads.length; index++) {
			Path file = Files.write(tempDir.resolve("murmur-" + index + ".bin"), payloads[index]);
			assertEquals(referenceCurseforgeMurmur(payloads[index]), HashUtils.getCurseforgeMurmurHash(file), "payload " + index);
		}
	}

	private static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 13 + 7);
		for (int i = 0; i < length; i += 11) bytes[i] = (byte) (i % 2 == 0 ? 0x20 : 0x0A);
		return bytes;
	}

	private static String referenceCurseforgeMurmur(byte[] data) {
		final int m = 0x5bd1e995;
		final int r = 24;
		long validLength = 0;
		for (byte b : data) {
			if (b != 0x9 && b != 0xA && b != 0xD && b != 0x20) validLength++;
		}
		long h = 1 ^ validLength;
		long k = 0;
		int shift = 0;
		for (byte b : data) {
			if (b == 0x9 || b == 0xA || b == 0xD || b == 0x20) continue;
			k |= (long) (b & 0xFF) << shift;
			shift += 8;
			if (shift == 32) {
				k = (k * m) & 0xFFFFFFFFL;
				k ^= k >>> r;
				k = (k * m) & 0xFFFFFFFFL;
				h = (h * m) & 0xFFFFFFFFL;
				h ^= k;
				k = 0;
				shift = 0;
			}
		}
		if (shift > 0) {
			h ^= k;
			h = (h * m) & 0xFFFFFFFFL;
		}
		h ^= h >>> 13;
		h = (h * m) & 0xFFFFFFFFL;
		h ^= h >>> 15;
		return String.valueOf(h);
	}
}
