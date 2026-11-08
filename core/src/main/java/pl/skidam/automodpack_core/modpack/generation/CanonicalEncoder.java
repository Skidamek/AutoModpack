package pl.skidam.automodpack_core.modpack.generation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import pl.skidam.automodpack_core.utils.HashUtils;

/** Small deterministic binary encoder used for public generation identities. */
final class CanonicalEncoder {
	private static final class EncodedBytes extends ByteArrayOutputStream {
		synchronized String sha1() {
			MessageDigest digest = HashUtils.newSha1Digest();
			digest.update(buf, 0, count);
			return HexFormat.of().formatHex(digest.digest());
		}
	}

	private final EncodedBytes bytes = new EncodedBytes();
	private final DataOutputStream output = new DataOutputStream(bytes);

	CanonicalEncoder string(String value) {
		return nullableString(value);
	}

	CanonicalEncoder nullableString(String value) {
		try {
			if (value == null) {
				output.writeByte(0);
				return this;
			}
			byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
			output.writeByte(1);
			output.writeInt(encoded.length);
			output.write(encoded);
			return this;
		} catch (IOException e) {
			throw new IllegalStateException("Failed to encode canonical value", e);
		}
	}

	CanonicalEncoder integer(int value) {
		try {
			output.writeInt(value);
			return this;
		} catch (IOException e) {
			throw new IllegalStateException("Failed to encode canonical value", e);
		}
	}

	CanonicalEncoder longValue(long value) {
		try {
			output.writeLong(value);
			return this;
		} catch (IOException e) {
			throw new IllegalStateException("Failed to encode canonical value", e);
		}
	}

	CanonicalEncoder bool(boolean value) {
		try {
			output.writeBoolean(value);
			return this;
		} catch (IOException e) {
			throw new IllegalStateException("Failed to encode canonical value", e);
		}
	}

	/** Hashes exactly the written prefix, without copying the complete canonical document into another array. */
	String sha1() {
		return bytes.sha1();
	}

	byte[] bytes() {
		return bytes.toByteArray();
	}
}
