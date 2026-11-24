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
 * Masks TLS record headers on the post-handoff holepunch stream with zero outer framing.
 *
 * <p>
 * Vanilla Minecraft applies {@code AES/CFB8/NoPadding} as the outermost wire layer after
 * {@code Encryption Response}, so an encrypted Minecraft connection carries no plaintext VarInt
 * length prefixes on the TCP stream. After the mcholepunch transport handoff drops {@code AES/CFB8}
 * in favor of TLS 1.3, writing raw TLS records would expose the plaintext five-byte TLS record
 * header ({@code 17 03 03 ...}), while wrapping each record in a plaintext VarInt would leak a
 * repeating length prefix every ~16 KiB (TLS 1.3's RFC 8446 record fragment limit, not a Minecraft
 * frame size).
 * </p>
 *
 * <p>
 * Instead, each five-byte TLS record header is XORed with a per-record AES-ECB counter block keyed
 * from the holepunch transport secret and direction, and the TLS ciphertext payload streams through
 * untouched. Both peers share an ordered TCP stream and implicit monotonic record counter (the same
 * model TLS 1.3 already relies on for AEAD nonces), so the wire adds zero bytes of framing overhead,
 * buffers at most five header bytes instead of a full 16 KiB record, and stays uniform ciphertext.
 * TLS remains the sole confidentiality and authentication boundary.
 * </p>
 */
final class TlsRecordCamouflage {
	static final int TLS_HEADER_LENGTH = 5;
	private static final int AES_BLOCK_LENGTH = 16;
	static final int MAX_RECORD_LENGTH = 16 * 1024 + 2048;
	private static final byte[] CLIENT_TO_SERVER = "AUTOMODPACK-TLS-HEADER-CTR/CLIENT-TO-SERVER".getBytes(StandardCharsets.UTF_8);
	private static final byte[] SERVER_TO_CLIENT = "AUTOMODPACK-TLS-HEADER-CTR/SERVER-TO-CLIENT".getBytes(StandardCharsets.UTF_8);

	private final Cipher maskCipher;
	private final byte[] header = new byte[TLS_HEADER_LENGTH];
	private final byte[] counterBlock = new byte[AES_BLOCK_LENGTH];
	private final byte[] maskBlock = new byte[AES_BLOCK_LENGTH];
	private int headerBytes;
	private int payloadRemaining;
	private long recordSequence;

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

	/** Takes plaintext TLS records in any chunking and emits header-masked records with zero framing overhead. */
	synchronized void encode(ByteBuffer input, ByteBuffer output) throws IOException {
		transform(input, output, true);
	}

	/** Takes header-masked TLS records in any chunking and restores plaintext TLS records. */
	synchronized void decode(ByteBuffer input, ByteBuffer output) throws IOException {
		transform(input, output, false);
	}

	/** Number of partial header bytes buffered across calls (0..4), bounding output expansion on the next call. */
	int pendingHeaderBytes() {
		return headerBytes;
	}

	private void transform(ByteBuffer input, ByteBuffer output, boolean encode) throws IOException {
		while (input.hasRemaining()) {
			if (payloadRemaining > 0) {
				int copy = Math.min(input.remaining(), payloadRemaining);
				if (output.remaining() < copy) {
					throw new IOException("TLS record camouflage output buffer is too small");
				}
				int savedLimit = input.limit();
				input.limit(input.position() + copy);
				output.put(input);
				input.limit(savedLimit);
				payloadRemaining -= copy;
				continue;
			}
			int take = Math.min(input.remaining(), TLS_HEADER_LENGTH - headerBytes);
			input.get(header, headerBytes, take);
			headerBytes += take;
			if (headerBytes < TLS_HEADER_LENGTH) {
				return;
			}
			if (output.remaining() < TLS_HEADER_LENGTH) {
				throw new IOException("TLS record camouflage output buffer is too small");
			}
			computeMask(recordSequence++);
			if (encode) {
				validateHeader(header);
				payloadRemaining = recordLength(header);
				for (int index = 0; index < TLS_HEADER_LENGTH; index++) {
					output.put((byte) (header[index] ^ maskBlock[index]));
				}
			} else {
				for (int index = 0; index < TLS_HEADER_LENGTH; index++) {
					header[index] ^= maskBlock[index];
				}
				validateHeader(header);
				payloadRemaining = recordLength(header);
				output.put(header, 0, TLS_HEADER_LENGTH);
			}
			headerBytes = 0;
		}
	}

	private void computeMask(long sequence) throws IOException {
		long value = sequence;
		for (int index = AES_BLOCK_LENGTH - 1; index >= 8; index--) {
			counterBlock[index] = (byte) value;
			value >>>= 8;
		}
		try {
			maskCipher.doFinal(counterBlock, 0, AES_BLOCK_LENGTH, maskBlock, 0);
		} catch (GeneralSecurityException exception) {
			throw new IOException("TLS record header mask failed", exception);
		}
	}

	private static void validateHeader(byte[] header) throws IOException {
		int contentType = header[0] & 0xff;
		if (contentType < 20 || contentType > 24) {
			throw new IOException("invalid TLS record content type: " + contentType);
		}
		int version = ((header[1] & 0xff) << 8) | (header[2] & 0xff);
		if (version < 0x0300 || version > 0x0304) {
			throw new IOException("invalid TLS record version: " + Integer.toHexString(version));
		}
		if (recordLength(header) > MAX_RECORD_LENGTH) {
			throw new IOException("TLS record is too large");
		}
	}

	private static int recordLength(byte[] header) {
		return ((header[3] & 0xff) << 8) | (header[4] & 0xff);
	}

	record Pair(TlsRecordCamouflage outbound, TlsRecordCamouflage inbound) {}
}
