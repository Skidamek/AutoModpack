package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLKeyException;
import javax.net.ssl.SSLSession;

/**
 * Carries opaque TLS bytes in a Minecraft-shaped VarInt21 frame envelope.
 *
 * <p>
 * Vanilla's frame length is a one-to-three-byte VarInt. The camouflage uses the full three-byte
 * VarInt21 width so the continuation bits can also be masked. Vanilla's decoder accepts this
 * overlong representation, and bulk TLS records already use the canonical three-byte width.
 * </p>
 *
 * <p>
 * This is traffic camouflage, not a security layer. AutoModpack derives its public seed from the
 * TLS server certificate, masks the frame header, and leaves TLS responsible for confidentiality,
 * integrity, and authentication.
 * </p>
 */
final class MinecraftFrameCamouflage {
	static final int HEADER_LENGTH = 3;
	static final int MAX_FRAME_LENGTH = (1 << 21) - 1;
	private static final byte[] DOMAIN = "AUTOMODPACK-MINECRAFT-FRAME-CAMOUFLAGE".getBytes(StandardCharsets.UTF_8);
	private static final byte[] CLIENT_TO_SERVER = "client-to-server".getBytes(StandardCharsets.UTF_8);
	private static final byte[] SERVER_TO_CLIENT = "server-to-client".getBytes(StandardCharsets.UTF_8);

	private final Mac mac;
	private final byte[] direction;
	private final byte[] header = new byte[HEADER_LENGTH];
	private final byte[] mask = new byte[HEADER_LENGTH];
	private int headerBytes;
	private int payloadBytes;
	private long sequence;

	private MinecraftFrameCamouflage(byte[] seed, byte[] direction) throws GeneralSecurityException {
		this.mac = Mac.getInstance("HmacSHA256");
		this.mac.init(new SecretKeySpec(seed, "HmacSHA256"));
		this.direction = direction;
		updateMask();
	}

	static Pair create(SSLSession session, boolean client) throws SSLKeyException {
		try {
			return fromSessionSeed(TlsRecordHeaderCamouflage.sessionSeed(session, client), client);
		} catch (GeneralSecurityException exception) {
			SSLKeyException failure = new SSLKeyException("Failed to initialize Minecraft frame camouflage");
			failure.initCause(exception);
			throw failure;
		}
	}

	static Pair fromSessionSeed(byte[] seed, boolean client) throws GeneralSecurityException {
		Objects.requireNonNull(seed, "seed");
		byte[] outboundDirection = client ? CLIENT_TO_SERVER : SERVER_TO_CLIENT;
		byte[] inboundDirection = client ? SERVER_TO_CLIENT : CLIENT_TO_SERVER;
		return new Pair(new MinecraftFrameCamouflage(seed, outboundDirection), new MinecraftFrameCamouflage(seed, inboundDirection));
	}

	synchronized void encode(ByteBuffer input, ByteBuffer output) throws IOException {
		int length = input.remaining();
		if (length == 0) return;
		if (length > MAX_FRAME_LENGTH) {
			throw new IOException("Minecraft frame is too large: " + length);
		}
		if (output.remaining() < HEADER_LENGTH + length) {
			throw new IOException("Minecraft frame output buffer is too small");
		}

		writeLength(length);
		for (int index = 0; index < HEADER_LENGTH; index++) {
			output.put((byte) (header[index] ^ mask[index]));
		}
		output.put(input);
		sequence++;
		updateMask();
	}

	synchronized void decode(ByteBuffer input, ByteBuffer output) throws IOException {
		while (input.hasRemaining()) {
			if (headerBytes < HEADER_LENGTH) {
				header[headerBytes] = (byte) (input.get() ^ mask[headerBytes]);
				headerBytes++;
				if (headerBytes == HEADER_LENGTH) {
					payloadBytes = readLength();
				}
				continue;
			}

			int copied = Math.min(input.remaining(), payloadBytes);
			if (output.remaining() < copied) {
				throw new IOException("Minecraft frame output buffer is too small");
			}
			ByteBuffer payload = input.slice();
			payload.limit(copied);
			output.put(payload);
			input.position(input.position() + copied);
			payloadBytes -= copied;
			if (payloadBytes == 0) {
				headerBytes = 0;
				sequence++;
				updateMask();
			}
		}
	}

	private void writeLength(int length) {
		header[0] = (byte) ((length & 0x7f) | 0x80);
		header[1] = (byte) (((length >>> 7) & 0x7f) | 0x80);
		header[2] = (byte) (length >>> 14);
	}

	private int readLength() throws IOException {
		int length = (header[0] & 0x7f) | ((header[1] & 0x7f) << 7) | ((header[2] & 0x7f) << 14);
		if (length == 0) {
			throw new IOException("Minecraft frame cannot be empty");
		}
		return length;
	}

	private void updateMask() {
		byte[] sequenceBytes = ByteBuffer.allocate(Long.BYTES).putLong(sequence).array();
		mac.reset();
		mac.update(DOMAIN);
		mac.update(direction);
		mac.update(sequenceBytes);
		byte[] digest = mac.doFinal();
		System.arraycopy(digest, 0, mask, 0, mask.length);
	}

	record Pair(MinecraftFrameCamouflage outbound, MinecraftFrameCamouflage inbound) {}
}
