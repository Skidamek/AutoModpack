package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLKeyException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Masks TLS record headers while leaving the TLS ciphertext payload untouched.
 *
 * <p>
 * The peer needs the same deterministic seed to recover record boundaries. Java 17 does not
 * expose a standard TLS exporter API, so the seed uses the public server certificate and negotiated
 * parameters. It is intentionally only a camouflage seed, not a secret. TLS remains the only
 * confidentiality and authentication boundary.
 * </p>
 */
final class TlsRecordHeaderCamouflage {
	private static final int HEADER_LENGTH = 5;
	private static final int MAX_RECORD_LENGTH = 16 * 1024 + 2048;
	private static final byte[] CLIENT_TO_SERVER = "client-to-server".getBytes(StandardCharsets.UTF_8);
	private static final byte[] SERVER_TO_CLIENT = "server-to-client".getBytes(StandardCharsets.UTF_8);

	private final Mac mac;
	private final byte[] direction;
	private final boolean encoding;
	private final byte[] header = new byte[HEADER_LENGTH];
	private final byte[] mask = new byte[HEADER_LENGTH];
	private int headerBytes;
	private int payloadBytes;
	private long sequence;

	private TlsRecordHeaderCamouflage(byte[] seed, byte[] direction, boolean encoding) throws GeneralSecurityException {
		this.mac = Mac.getInstance("HmacSHA256");
		this.mac.init(new SecretKeySpec(seed, "HmacSHA256"));
		this.direction = direction;
		this.encoding = encoding;
		updateMask();
	}

	static Pair create(SSLSession session, boolean client) throws SSLKeyException {
		try {
			return fromSessionSeed(sessionSeed(session, client), client);
		} catch (GeneralSecurityException exception) {
			SSLKeyException failure = new SSLKeyException("Failed to initialize TLS record camouflage");
			failure.initCause(exception);
			throw failure;
		}
	}

	static Pair fromSessionSeed(byte[] sessionSeed, boolean client) throws GeneralSecurityException {
		Objects.requireNonNull(sessionSeed, "sessionSeed");
		byte[] outboundDirection = client ? CLIENT_TO_SERVER : SERVER_TO_CLIENT;
		byte[] inboundDirection = client ? SERVER_TO_CLIENT : CLIENT_TO_SERVER;
		return new Pair(new TlsRecordHeaderCamouflage(sessionSeed, outboundDirection, true), new TlsRecordHeaderCamouflage(sessionSeed, inboundDirection, false));
	}

	static byte[] sessionSeed(SSLSession session, boolean client) throws GeneralSecurityException, SSLKeyException {
		Objects.requireNonNull(session, "session");
		Certificate[] certificates;
		try {
			certificates = client ? session.getPeerCertificates() : session.getLocalCertificates();
		} catch (SSLPeerUnverifiedException exception) {
			SSLKeyException failure = new SSLKeyException("TLS session does not expose the server certificate");
			failure.initCause(exception);
			throw failure;
		}
		if (certificates == null || certificates.length == 0) {
			throw new SSLKeyException("TLS session does not expose the server certificate");
		}
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update("AUTOMODPACK-TLS-RECORD-CAMOUFLAGE".getBytes(StandardCharsets.UTF_8));
		digest.update(session.getProtocol().getBytes(StandardCharsets.UTF_8));
		digest.update(session.getCipherSuite().getBytes(StandardCharsets.UTF_8));
		digest.update(certificates[0].getEncoded());
		return digest.digest();
	}

	synchronized void transform(ByteBuffer input, ByteBuffer output) throws IOException {
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(output, "output");
		if (output.remaining() < input.remaining()) {
			throw new IOException("TLS camouflage output buffer is too small");
		}
		while (input.hasRemaining()) {
			if (headerBytes < HEADER_LENGTH) {
				byte value = input.get();
				byte plain = encoding ? value : (byte) (value ^ mask[headerBytes]);
				header[headerBytes] = plain;
				output.put(encoding ? (byte) (value ^ mask[headerBytes]) : plain);
				headerBytes++;
				if (headerBytes == HEADER_LENGTH) {
					validateHeader();
				}
				continue;
			}

			int copied = Math.min(input.remaining(), payloadBytes);
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

	private void validateHeader() throws IOException {
		int length = ((header[3] & 0xff) << 8) | (header[4] & 0xff);
		if (length > MAX_RECORD_LENGTH) {
			throw new IOException("TLS record is too large: " + length);
		}
		int contentType = header[0] & 0xff;
		if (contentType < 20 || contentType > 24) {
			throw new IOException("invalid TLS record content type: " + contentType);
		}
		int version = ((header[1] & 0xff) << 8) | (header[2] & 0xff);
		if (version < 0x0300 || version > 0x0304) {
			throw new IOException("invalid TLS record version: " + Integer.toHexString(version));
		}
		payloadBytes = length;
	}

	private void updateMask() {
		byte[] sequenceBytes = ByteBuffer.allocate(Long.BYTES).putLong(sequence).array();
		mac.reset();
		mac.update(direction);
		mac.update(sequenceBytes);
		byte[] digest = mac.doFinal();
		System.arraycopy(digest, 0, mask, 0, mask.length);
	}

	record Pair(TlsRecordHeaderCamouflage outbound, TlsRecordHeaderCamouflage inbound) {}
}
