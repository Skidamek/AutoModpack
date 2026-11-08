package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import org.junit.jupiter.api.Test;

class CanonicalEncoderTest {
	@Test
	void directDigestMatchesTheOriginalBinaryEncodingWithoutFinalizingIt() throws Exception {
		CanonicalEncoder encoder = new CanonicalEncoder();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream legacy = new DataOutputStream(bytes);
		String[] strings = {null, "", "plain ASCII", "żółć/😀", "\ud800", "\u0000", "x".repeat(511)};
		Random random = new Random(71298);
		for (int i = 0; i < 500; i++) {
			String value = strings[i % strings.length];
			encoder.nullableString(value);
			legacy.writeByte(value == null ? 0 : 1);
			if (value != null) {
				byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
				legacy.writeInt(utf8.length);
				legacy.write(utf8);
			}
			int integer = random.nextInt();
			long number = random.nextLong();
			boolean flag = random.nextBoolean();
			encoder.integer(integer).longValue(number).bool(flag);
			legacy.writeInt(integer);
			legacy.writeLong(number);
			legacy.writeBoolean(flag);
			if (i % 17 == 0 || i == 499) {
				byte[] expected = bytes.toByteArray();
				assertArrayEquals(expected, encoder.bytes());
				String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(expected));
				assertEquals(hash, encoder.sha1());
				assertEquals(hash, encoder.sha1(), "hashing does not reset or expose the buffer");
			}
		}
	}
}
