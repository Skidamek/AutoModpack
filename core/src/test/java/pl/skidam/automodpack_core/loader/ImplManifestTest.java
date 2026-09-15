package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class ImplManifestTest {
	private static final byte[] GENERATION = testSha1("all-zst");
	private static final byte[] SLICE_A_SHA1 = testSha1("slice-a");
	private static final byte[] SLICE_B_SHA1 = testSha1("slice-b");

	@Test
	void roundTripsEntries() {
		byte[] bytes = manifest(2);
		ImplManifest manifest = ImplManifest.parse(bytes);

		assertEquals(HexFormat.of().formatHex(GENERATION), manifest.generation());
		assertEquals(2, manifest.entries().size());
		ImplManifest.Entry first = manifest.entry("1.20.1-fabric");
		assertEquals(1024, first.offset());
		assertEquals(8192, first.length());
		assertEquals(HexFormat.of().formatHex(SLICE_A_SHA1), first.sha1());
		ImplManifest.Entry second = manifest.entry("26.2-neoforge");
		assertEquals(1024L + 8192L, second.offset());
		assertEquals(1, second.length());
		assertEquals(8192L + 1, manifest.totalSize());
	}

	@Test
	void rejectsBadMagic() {
		byte[] bytes = manifest(1);
		bytes[0] = 'X';
		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> ImplManifest.parse(bytes));
		assertTrue(thrown.getMessage().contains("magic"));
	}

	@Test
	void rejectsTruncation() {
		byte[] bytes = manifest(1);
		byte[] truncated = new byte[bytes.length - 4];
		System.arraycopy(bytes, 0, truncated, 0, truncated.length);
		assertThrows(IllegalStateException.class, () -> ImplManifest.parse(truncated));
	}

	@Test
	void unknownIdCrashesWithTheManifestIds() {
		ImplManifest manifest = ImplManifest.parse(manifest(2));
		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> manifest.entry("1.12.2-forge"));
		assertTrue(thrown.getMessage().contains("1.20.1-fabric") && thrown.getMessage().contains("26.2-neoforge"));
	}

	private static byte[] manifest(int count) {
		ByteBuffer buffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put(ImplManifest.MAGIC);
		buffer.put(GENERATION);
		buffer.putShort((short) count);
		buffer.putShort((short) "1.20.1-fabric".length());
		buffer.put("1.20.1-fabric".getBytes(StandardCharsets.UTF_8));
		buffer.putInt(1024);
		buffer.putInt(8192);
		buffer.put(SLICE_A_SHA1);
		if (count > 1) {
			buffer.putShort((short) "26.2-neoforge".length());
			buffer.put("26.2-neoforge".getBytes(StandardCharsets.UTF_8));
			buffer.putInt(1024 + 8192);
			buffer.putInt(1);
			buffer.put(SLICE_B_SHA1);
		}
		return Arrays.copyOf(buffer.array(), buffer.position());
	}

	private static byte[] testSha1(String seed) {
		try {
			return MessageDigest.getInstance("SHA-1").digest(seed.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
