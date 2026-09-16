package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLKeyException;

/**
 * Carries TLS records in a vanilla-shaped frame envelope with the record header encrypted.
 *
 * <p>
 * Each TLS record becomes one Minecraft-shaped frame: a plain canonical VarInt length, byte for
 * byte the length prefix vanilla itself writes, followed by the record whose five-byte header is
 * XORed with an AES-ECB keystream block keyed by a secret derived from the holepunch transport
 * secret and sampled from the last sixteen bytes of the record payload, QUIC header protection
 * style. The record payload stays exactly as TLS produced it, so TLS remains the only
 * confidentiality and authentication boundary; the header encryption hides the protocol transition
 * from a passive observer of the framed stream. Encrypted records always carry at least seventeen
 * payload bytes, so the zero-padded short-sample fallback is unreachable in practice.
 * </p>
 */
final class TlsRecordCamouflage {
	private static final int TLS_HEADER_LENGTH = 5;
	static final int FRAME_HEADER_LENGTH = 3;
	private static final int SAMPLE_LENGTH = 16;
	static final int MAX_RECORD_LENGTH = 16 * 1024 + 2048;
	private static final byte[] CLIENT_TO_SERVER = "AUTOMODPACK-TLS-RECORD-KEY/CLIENT-TO-SERVER".getBytes(StandardCharsets.UTF_8);
	private static final byte[] SERVER_TO_CLIENT = "AUTOMODPACK-TLS-RECORD-KEY/SERVER-TO-CLIENT".getBytes(StandardCharsets.UTF_8);

	private final Cipher maskCipher;
	private final byte[] header = new byte[TLS_HEADER_LENGTH];
	private final byte[] frameHeader = new byte[FRAME_HEADER_LENGTH];
	private byte[] record;
	private int headerBytes;
	private int recordBytes;
	private int frameHeaderBytes;
	private boolean frameHeaderComplete;

	private TlsRecordCamouflage(byte[] maskKey) throws GeneralSecurityException {
		maskCipher = Cipher.getInstance("AES/ECB/NoPadding");
		maskCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Objects.requireNonNull(maskKey, "maskKey"), "AES"));
	}

	static Pair create(byte[] transportSecret, boolean client) throws SSLKeyException {
		Objects.requireNonNull(transportSecret, "transportSecret");
		try {
			byte[] outboundKey = key(transportSecret, client ? CLIENT_TO_SERVER : SERVER_TO_CLIENT);
			byte[] inboundKey = key(transportSecret, client ? SERVER_TO_CLIENT : CLIENT_TO_SERVER);
			return new Pair(new TlsRecordCamouflage(outboundKey), new TlsRecordCamouflage(inboundKey));
		} catch (GeneralSecurityException exception) {
			SSLKeyException failure = new SSLKeyException("Failed to initialize TLS record camouflage");
			failure.initCause(exception);
			throw failure;
		}
	}

	private static byte[] key(byte[] transportSecret, byte[] direction) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(transportSecret, "HmacSHA256"));
		return mac.doFinal(direction);
	}

	/** Takes plaintext TLS records in any chunking and emits one masked frame per complete record. */
	synchronized void encode(ByteBuffer input, ByteBuffer output) throws IOException {
		while (input.hasRemaining()) {
			if (headerBytes < TLS_HEADER_LENGTH) {
				header[headerBytes++] = input.get();
				if (headerBytes == TLS_HEADER_LENGTH) {
					validateHeader(header);
					record = new byte[TLS_HEADER_LENGTH + recordLength(header)];
					System.arraycopy(header, 0, record, 0, TLS_HEADER_LENGTH);
					recordBytes = TLS_HEADER_LENGTH;
				}
				continue;
			}
			int copied = Math.min(input.remaining(), record.length - recordBytes);
			input.get(record, recordBytes, copied);
			recordBytes += copied;
			if (recordBytes == record.length) {
				emitFrame(output);
			}
		}
	}

	/** Takes masked frames in any chunking and emits plaintext TLS records. */
	synchronized void decode(ByteBuffer input, ByteBuffer output) throws IOException {
		while (input.hasRemaining()) {
			if (!frameHeaderComplete) {
				if (frameHeaderBytes == FRAME_HEADER_LENGTH) {
					throw new IOException("TLS record frame length VarInt is too long");
				}
				byte next = input.get();
				frameHeader[frameHeaderBytes++] = next;
				if ((next & 0x80) != 0) {
					continue;
				}
				int frameLength = readFrameHeader();
				if (frameLength < TLS_HEADER_LENGTH + 1 || frameLength > TLS_HEADER_LENGTH + MAX_RECORD_LENGTH) {
					throw new IOException("TLS record frame is out of range: " + frameLength);
				}
				record = new byte[frameLength];
				recordBytes = 0;
				frameHeaderComplete = true;
				continue;
			}
			int copied = Math.min(input.remaining(), record.length - recordBytes);
			input.get(record, recordBytes, copied);
			recordBytes += copied;
			if (recordBytes == record.length) {
				emitRecord(output);
			}
		}
	}

	private int readFrameHeader() {
		int length = 0;
		for (int index = 0; index < frameHeaderBytes; index++) {
			length |= (frameHeader[index] & 0x7f) << (index * 7);
		}
		return length;
	}

	private void emitFrame(ByteBuffer output) throws IOException {
		int headerLength = frameHeaderLength(record.length);
		if (output.remaining() < headerLength + record.length) {
			throw new IOException("TLS record camouflage output buffer is too small");
		}
		int value = record.length;
		while (true) {
			if ((value & ~0x7f) == 0) {
				output.put((byte) value);
				break;
			}
			output.put((byte) ((value & 0x7f) | 0x80));
			value >>>= 7;
		}
		byte[] mask = mask(sample());
		for (int index = 0; index < TLS_HEADER_LENGTH; index++) {
			output.put((byte) (record[index] ^ mask[index]));
		}
		output.put(record, TLS_HEADER_LENGTH, record.length - TLS_HEADER_LENGTH);
		reset();
	}

	private void emitRecord(ByteBuffer output) throws IOException {
		byte[] mask = mask(sample());
		for (int index = 0; index < TLS_HEADER_LENGTH; index++) {
			record[index] ^= mask[index];
		}
		validateHeader(record);
		int declaredLength = ((record[3] & 0xff) << 8) | (record[4] & 0xff);
		if (declaredLength != record.length - TLS_HEADER_LENGTH) {
			throw new IOException("TLS record header does not match its frame: " + declaredLength + " != " + (record.length - TLS_HEADER_LENGTH));
		}
		if (output.remaining() < record.length) {
			throw new IOException("TLS record camouflage output buffer is too small");
		}
		output.put(record);
		reset();
	}

	/** Total size of the record currently being reassembled, or zero when sitting on a frame boundary. */
	int pendingRecordLength() {
		return record != null ? record.length : 0;
	}

	/** The last sixteen payload bytes, zero-padded on the left. */
	private byte[] sample() {
		byte[] sample = new byte[SAMPLE_LENGTH];
		int tail = Math.min(SAMPLE_LENGTH, record.length - TLS_HEADER_LENGTH);
		System.arraycopy(record, record.length - tail, sample, SAMPLE_LENGTH - tail, tail);
		return sample;
	}

	private byte[] mask(byte[] sample) throws IOException {
		try {
			return maskCipher.doFinal(sample);
		} catch (GeneralSecurityException exception) {
			throw new IOException("TLS record header mask failed", exception);
		}
	}

	private void validateHeader(byte[] header) throws IOException {
		int contentType = header[0] & 0xff;
		if (contentType < 20 || contentType > 24) {
			throw new IOException("invalid TLS record content type: " + contentType);
		}
		int version = ((header[1] & 0xff) << 8) | (header[2] & 0xff);
		if (version < 0x0300 || version > 0x0304) {
			throw new IOException("invalid TLS record version: " + Integer.toHexString(version));
		}
		if (((header[3] & 0xff) << 8 | header[4] & 0xff) > MAX_RECORD_LENGTH) {
			throw new IOException("TLS record is too large");
		}
	}

	private int recordLength(byte[] header) {
		return ((header[3] & 0xff) << 8) | (header[4] & 0xff);
	}

	private static int frameHeaderLength(int frameLength) {
		return frameLength >>> 14 != 0 ? 3 : frameLength >>> 7 != 0 ? 2 : 1;
	}

	private void reset() {
		record = null;
		headerBytes = 0;
		recordBytes = 0;
		frameHeaderBytes = 0;
		frameHeaderComplete = false;
	}

	record Pair(TlsRecordCamouflage outbound, TlsRecordCamouflage inbound) {}
}
