package pl.skidam.automodpack_core.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Minimal ASN.1 DER reader and writer (ITU-T X.690) for X.509 certificates and CRL extensions. */
final class Der {
	static final int TAG_INTEGER = 0x02;
	static final int TAG_BIT_STRING = 0x03;
	static final int TAG_OCTET_STRING = 0x04;
	static final int TAG_NULL = 0x05;
	static final int TAG_OID = 0x06;
	static final int TAG_UTC_TIME = 0x17;
	static final int TAG_GENERALIZED_TIME = 0x18;
	static final int TAG_SEQUENCE = 0x30;
	static final int TAG_CONTEXT_PRIMITIVE_6 = 0x86;
	static final int TAG_CONTEXT_CONSTRUCTED_0 = 0xA0;

	static final byte[] NULL = new byte[]{TAG_NULL, 0x00};

	private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter GENERALIZED_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

	private Der() {}

	record Element(int tag, byte[] content) {
		BigInteger asInteger() throws IOException {
			if (tag != TAG_INTEGER || content.length == 0) throw new IOException("Expected DER INTEGER");
			if (content.length >= 2 && ((content[0] == 0 && (content[1] & 0x80) == 0) || (content[0] == -1 && (content[1] & 0x80) != 0))) {
				throw new IOException("Non-minimal DER INTEGER");
			}
			return new BigInteger(content);
		}

		List<Element> children() throws IOException {
			return readAll(content);
		}
	}

	static byte[] tlv(int tag, byte[] content) {
		byte[] length = encodeLength(content.length);
		byte[] out = new byte[1 + length.length + content.length];
		out[0] = (byte) tag;
		System.arraycopy(length, 0, out, 1, length.length);
		System.arraycopy(content, 0, out, 1 + length.length, content.length);
		return out;
	}

	static byte[] sequence(byte[]... elements) {
		return tlv(TAG_SEQUENCE, concat(elements));
	}

	static byte[] integer(BigInteger value) {
		return tlv(TAG_INTEGER, value.toByteArray());
	}

	static byte[] bitString(byte[] bytes) {
		byte[] body = new byte[bytes.length + 1];
		body[0] = 0;
		System.arraycopy(bytes, 0, body, 1, bytes.length);
		return tlv(TAG_BIT_STRING, body);
	}

	/** RFC 5280 section 4.1.2.5: UTCTime through 2049, GeneralizedTime from 2050 onward. */
	static byte[] time(Instant instant) {
		int year = instant.atZone(ZoneOffset.UTC).getYear();
		if (year >= 1950 && year <= 2049) {
			return tlv(TAG_UTC_TIME, UTC_TIME.format(instant).getBytes(StandardCharsets.US_ASCII));
		}
		return tlv(TAG_GENERALIZED_TIME, GENERALIZED_TIME.format(instant).getBytes(StandardCharsets.US_ASCII));
	}

	static Element readSingle(byte[] der) throws IOException {
		List<Element> elements = readAll(der);
		if (elements.size() != 1) throw new IOException("Expected 1 DER element, got " + elements.size());
		return elements.get(0);
	}

	static List<Element> readAll(byte[] der) throws IOException {
		List<Element> elements = new ArrayList<>();
		int pos = 0;
		while (pos < der.length) {
			int tag = der[pos++] & 0xFF;
			if ((tag & 0x1F) == 0x1F) throw new IOException("High-tag-number DER tags are not supported");
			if (pos >= der.length) throw new IOException("Truncated DER length");
			int lenByte = der[pos++] & 0xFF;
			int length;
			if ((lenByte & 0x80) == 0) {
				length = lenByte;
			} else {
				int numBytes = lenByte & 0x7F;
				if (numBytes == 0 || numBytes > 4 || pos + numBytes > der.length) throw new IOException("Invalid DER length");
				if ((der[pos] & 0xFF) == 0) throw new IOException("Non-canonical DER length");
				length = 0;
				for (int i = 0; i < numBytes; i++) {
					length = (length << 8) | (der[pos++] & 0xFF);
				}
				if (length < 128) throw new IOException("Non-canonical DER length");
			}
			if (length < 0 || length > der.length - pos) throw new IOException("Truncated DER content");
			elements.add(new Element(tag, Arrays.copyOfRange(der, pos, pos + length)));
			pos += length;
		}
		return elements;
	}

	/** Unwraps the outer DER OCTET STRING that Java's X509Extension.getExtensionValue returns. */
	static Element unwrapExtension(byte[] extensionValue) throws IOException {
		Element outer = readSingle(extensionValue);
		if (outer.tag() != TAG_OCTET_STRING) throw new IOException("Expected DER OCTET STRING for extension value");
		return readSingle(outer.content());
	}

	private static byte[] encodeLength(int length) {
		if (length < 128) return new byte[]{(byte) length};
		if (length <= 0xFF) return new byte[]{(byte) 0x81, (byte) length};
		if (length <= 0xFFFF) return new byte[]{(byte) 0x82, (byte) (length >> 8), (byte) length};
		if (length <= 0xFFFFFF) return new byte[]{(byte) 0x83, (byte) (length >> 16), (byte) (length >> 8), (byte) length};
		return new byte[]{(byte) 0x84, (byte) (length >> 24), (byte) (length >> 16), (byte) (length >> 8), (byte) length};
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) out.writeBytes(part);
		return out.toByteArray();
	}
}
