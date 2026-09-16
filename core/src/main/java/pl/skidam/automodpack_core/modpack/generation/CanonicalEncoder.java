package pl.skidam.automodpack_core.modpack.generation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Small deterministic binary encoder used for public generation identities. */
final class CanonicalEncoder {
	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
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

	byte[] bytes() {
		return bytes.toByteArray();
	}
}
