package pl.skidam.automodpack_core.protocol;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.security.auth.x500.X500Principal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.utils.DurableFiles;

public class NetUtils {
	private static final Logger LOGGER = LogManager.getLogger(NetUtils.class);
	private static final byte[] HOLEPUNCH_MARKER_CONSTANT = computeHolepunchMarkerConstant();

	/** AutoModpack's 80-bit mcholepunch routing constant, kept off Netty-importing server classes so client preload can read it without Netty on the classpath. */
	public static byte[] holepunchMarkerConstant() {
		return HOLEPUNCH_MARKER_CONSTANT.clone();
	}

	private static byte[] computeHolepunchMarkerConstant() {
		try {
			return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest("automodpack:holepunch-marker".getBytes(StandardCharsets.UTF_8)), 10);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is missing from the JRE", exception);
		}
	}

	private static final String USER_AGENT_PREFIX = "github/skidamek/automodpack/";

	/** Resolved on demand: a detached helper makes requests before any environment exists. */
	public static String userAgent() {
		Environment environment = Environment.installed();
		return USER_AGENT_PREFIX + (environment == null ? "unknown" : environment.modVersion());
	}
	public static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(15);
	// The configured-connection read deadline also guards bulk file transfers, where legitimate
	// flow-control pauses outlast a connect-grade deadline. It only has to catch a dead peer, not
	// a slow pipe, so it sits far past any healthy inter-frame gap.
	public static final Duration TRANSFER_IDLE_TIMEOUT = Duration.ofSeconds(60);
	// The transfer write-stall tripwire: how long a frame may sit on the socket with zero drain
	// progress before the peer is declared gone. Only a peer that stopped reading entirely can trip
	// it - a live link resets the window with every completed write, and at the receipted drain
	// floor (the 20-client share of a 5 Mbps uplink, ~31 KB/s per client) a STREAM_WRITE_BYTES write
	// completes at least every ~17 s, 5x inside this window. A genuinely dead peer also surfaces
	// through its own 60 s read deadline closing the socket, so this fuse is never the first thing
	// to fire on a healthy connection. This 20-client envelope is load-bearing for the client's
	// trickle fuse floor (TAKE_RATE_FLOOR_BYTES_PER_SECOND): changing it requires re-deriving that floor.
	public static final Duration TRANSFER_WRITE_STALL_TIMEOUT = Duration.ofSeconds(90);
	// The idle reap for public contract connections, in seconds of no reads and no writes. It sits far past any
	// client's keep-alive reuse window while staying inside a minute-scale patience for silent sockets, and a streamed
	// response completes a STREAM_WRITE_BYTES write at least every ~17 s at the drain floor (~31 KB/s per client),
	// 3.5x inside this window - so the reap never interrupts a live transfer. The same envelope is load-bearing for
	// the client's trickle fuse floor (TAKE_RATE_FLOOR_BYTES_PER_SECOND): changing it requires re-deriving that floor.
	public static final int HTTP_IDLE_REAP_SECONDS = 60;
	// The client's trickle fuse floor: a take draining under this rate is fused past its takeBudgetNanos, counted from
	// the take's FIRST drained byte - queueing behind a lane's serially served predecessors is the flow control working,
	// and only an actively draining take can prove a trickle. The floor sits just below the documented per-lane
	// congested share: 6.25 KiB/s = 5 mbit / 20 clients / 5 lanes, where a 4 MiB take needs ~655 s - at the old
	// 16 KiB/s floor that regime mass-failed against its 256 s budget. 4 KiB/s gives a uniform 1.56x margin because
	// takeBudgetNanos is proportional above the 90 s stall floor, and it still holds ~30 clients on the reference
	// uplink: the 30-client share is 5 mbit / 30 clients / 5 lanes = ~4.2 KiB/s per lane, just above the floor. The
	// trade, said out loud: a 5 KiB/s dripping host now completes a 4 MiB take in ~819 s (~14 minutes) -
	// slow-but-completing with cancel is the mitigation, genuinely dead peers still die at the 60 s read deadline,
	// and this fuse is a long-tail backstop against sub-floor drips, not a fast-fail.
	public static final int TAKE_RATE_FLOOR_BYTES_PER_SECOND = 4 * 1024;
	// Pre-configuration keepalive cadence: NAT mappings and holepunch relay bindings typically decay after 30-60s of
	// silence, so a 20s heartbeat sits well inside that band while costing the parked client one tiny ranged GET.
	public static final int NETWORK_TIMEOUT_MILLIS = Math.toIntExact(NETWORK_TIMEOUT.toMillis());
	public static final int TRANSFER_IDLE_TIMEOUT_MILLIS = Math.toIntExact(TRANSFER_IDLE_TIMEOUT.toMillis());

	public static final int MAGIC_AMMH = 0x414D4D48;
	public static final int MAGIC_AMOK = 0x414D4F4B;

	// The ranged-GET unit the client tiles objects with; changing it changes request granularity on the client's
	// lanes. Per-request overhead at this size is noise - a few hundred bytes of headers and one seek per 4 MiB - so
	// the unit is sized by the wire, not by either end's buffers. The server's streamed-write granularity is
	// STREAM_WRITE_BYTES below.
	public static final int WIRE_CHUNK_BYTES = 4 * 1024 * 1024; // 4 MiB

	// The per-connection unsettled-bytes pipeline window, replacing the old fixed 8-deep cap (8 takes = 32 MiB, which
	// made a pack of tiny files pay one round trip per 40 files). Four 4 MiB takes - the largest take - may sit
	// unsettled per lane, so five lanes hold 80 MiB: 2.1x the 37.5 MB bandwidth-delay product of 1 Gbit at 300 ms
	// and far above the reference 5 mbit envelope's BDP, while a pack of 16 KiB files still fits a whole lane's
	// window thousands of requests over and drains in one round-trip generation (the tiny-files bench: 800 files,
	// 300 ms delay, ~2 round-trip generations). The size is also the loss guardrail: every dropped segment stalls
	// exactly the bytes queued behind it, so a deep lane queue turns one lost packet into tens of megabytes of
	// delayed delivery and multi-megabyte restarts - measured at 1% loss, 64 MiB lanes downloaded the reference
	// pack in ~227 s where 16 MiB lanes with the adaptive take bound below return to the low 130s. The count
	// tripwire beside it keeps the bookkeeping bounded.
	public static final long PIPELINE_WINDOW_BYTES = 16L * 1024 * 1024;

	// The per-connection count tripwire beside the byte window: 5 KB takes reach 10 MB unsettled per lane, so five
	// lanes hold 50 MB - above the 1 Gbit/300 ms BDP even for that smallest realistic shape - while per-lane
	// bookkeeping (futures, per-request state) stays around 2 MB. Good components never touch it.
	public static final int PIPELINE_MAX_REQUESTS = 2048;

	// The server's streamed-write granularity. The idle reap and the stall fuse see write COMPLETIONS, so the chunk
	// must be small enough that a draining client keeps completing writes: at the receipted drain floor - the
	// 20-client share of a 5 Mbps uplink, ~31 KB/s per client - a 512 KiB write completes at least every ~17 s,
	// 3.5x inside the 60 s reap and 5x inside the 90 s stall fuse. A 4 MiB chunk would need ~135 s and reap live
	// transfers.
	public static final int STREAM_WRITE_BYTES = 512 * 1024;

	// The client's per-response read buffer, deliberately not the transfer unit: a 512 KiB read costs a syscall per
	// ~5 ms of drain at 100 MB/s, and five lanes pin 2.5 MiB of heap instead of 20.
	public static final int READ_BUFFER_BYTES = 512 * 1024;

	// The server queues streamed response bytes ahead of the peer's drain: writes pause at the high watermark, resume
	// below the low one, so compression overlaps the wire. The receipt is per connection and the server hosts every
	// client: against the reference envelope (5 Mbps uplink, 300 ms RTT, BDP ≈ 187 KB) a 512 KiB queue holds ~2.7 BDP,
	// which is everything the pipe can absorb, and a full pool of 20 clients × 5 lanes queues ≤ 50 MiB on top of the
	// one 4 MiB chunk each stream holds transiently - where a 4 MiB watermark queued ~400 MiB across the same pool.
	public static final int WRITE_BUFFER_LOW_WATER = 256 * 1024;
	public static final int WRITE_BUFFER_HIGH_WATER = 512 * 1024;

	// A self-signed leaf is trusted by its pin, DNS record, or the player, never by its dates; this only has to outlive the server.
	private static final int SELF_SIGNED_VALIDITY_YEARS = 100;

	public static String getFingerprint(X509Certificate cert) throws CertificateEncodingException {
		byte[] certificate = cert.getEncoded();

		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] fingerprint = digest.digest(certificate);
			return HexFormat.of().formatHex(fingerprint).toLowerCase(Locale.ROOT);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	private static final Pattern FINGERPRINT_SEPARATORS = Pattern.compile("[\\s:-]");
	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

	public static String normalizeFingerprint(String fingerprint) {
		String normalized = fingerprint == null ? "" : fingerprint.replace(":", "").trim().toLowerCase(Locale.ROOT);
		if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Certificate fingerprint must be 64 hexadecimal characters");
		return normalized;
	}

	public static boolean matchesFingerprint(String input, String expectedFingerprint) {
		if (input == null || expectedFingerprint == null) return false;
		String normalizedInput = FINGERPRINT_SEPARATORS.matcher(input).replaceAll("").toLowerCase(Locale.ROOT);
		String normalizedExpected = FINGERPRINT_SEPARATORS.matcher(expectedFingerprint).replaceAll("").toLowerCase(Locale.ROOT);
		return SHA256_HEX.matcher(normalizedInput).matches() && SHA256_HEX.matcher(normalizedExpected).matches() && normalizedInput.equals(normalizedExpected);
	}

	public static String shortenFingerprint(String fingerprint) {
		if (fingerprint == null || fingerprint.length() <= 19) return fingerprint;
		return fingerprint.substring(0, 8) + "…" + fingerprint.substring(fingerprint.length() - 8);
	}

	public static String shortenFingerprint(String fingerprint, int visibleCharactersPerSide) {
		if (fingerprint == null || visibleCharactersPerSide < 1 || fingerprint.length() <= visibleCharactersPerSide * 2 + 3) return fingerprint;
		return fingerprint.substring(0, visibleCharactersPerSide) + "..." + fingerprint.substring(fingerprint.length() - visibleCharactersPerSide);
	}

	public static KeyPair generateKeyPair() throws Exception {
		KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
		keyPairGenerator.initialize(2048);
		return keyPairGenerator.generateKeyPair();
	}

	private static final byte[] ALG_SHA256_WITH_RSA = Der.sequence(Der.oid("1.2.840.113549.1.1.11"), Der.NULL);
	private static final byte[] ALG_SHA256_WITH_ECDSA = Der.sequence(Der.oid("1.2.840.10045.4.3.2"));
	private static final byte[] X509_V3 = Der.tlv(Der.TAG_CONTEXT_CONSTRUCTED_0, Der.integer(BigInteger.TWO));
	private static final BigInteger THREE = BigInteger.valueOf(3);

	public static X509Certificate selfSign(KeyPair keyPair) throws Exception {
		Instant start = Instant.now();
		Instant end = start.atZone(ZoneOffset.UTC).plusYears(SELF_SIGNED_VALIDITY_YEARS).toInstant();
		byte[] name = new X500Principal("CN=AutoModpack Self Signed Certificate").getEncoded();
		BigInteger serial = new BigInteger(159, new SecureRandom()).setBit(158);

		String algorithm = signatureAlgorithm(keyPair.getPrivate());
		byte[] algorithmId = switch (algorithm) {
			case "SHA256withRSA" -> ALG_SHA256_WITH_RSA;
			case "SHA256withECDSA" -> ALG_SHA256_WITH_ECDSA;
			default -> throw new GeneralSecurityException("Cannot self-sign with " + algorithm);
		};

		byte[] validity = Der.sequence(Der.time(start), Der.time(end));
		byte[] tbs = Der.sequence(X509_V3, Der.integer(serial), algorithmId, name, validity, name, keyPair.getPublic().getEncoded());

		Signature signature = Signature.getInstance(algorithm);
		signature.initSign(keyPair.getPrivate());
		signature.update(tbs);
		byte[] encodedCertificate = Der.sequence(tbs, algorithmId, Der.bitString(signature.sign()));

		X509Certificate certificate;
		try (InputStream input = new ByteArrayInputStream(encodedCertificate)) {
			certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
		}
		certificate.verify(keyPair.getPublic());
		return certificate;
	}

	public static void saveCertificate(X509Certificate cert, Path path) throws Exception {
		String certPem = "-----BEGIN CERTIFICATE-----\n" + formatBase64(cert.getEncoded()) + "-----END CERTIFICATE-----\n";
		DurableFiles.writeAtomic(path, certPem.getBytes(StandardCharsets.UTF_8));
	}

	public static X509Certificate loadCertificate(Path path) throws Exception {
		if (!Files.exists(path)) return null;
		try (InputStream in = Files.newInputStream(path)) {
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			return (X509Certificate) cf.generateCertificate(in);
		}
	}

	public static void savePrivateKey(PrivateKey key, Path path) throws Exception {
		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(key.getEncoded());
		String keyPem = "-----BEGIN PRIVATE KEY-----\n" + formatBase64(keySpec.getEncoded()) + "-----END PRIVATE KEY-----\n";
		DurableFiles.writeAtomicPrivate(path, keyPem.getBytes(StandardCharsets.UTF_8));
	}

	/** Closes and swallows the failure: teardown paths never have a better story than the error they are already telling. */
	public static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) return;
		try {
			closeable.close();
		} catch (Exception ignored) {
		}
	}

	/** Reads the PKCS#8 PEM {@link #savePrivateKey} writes; null when the file is missing, a GeneralSecurityException when it is present but unreadable as any supported key type. */
	public static PrivateKey loadPrivateKey(Path path) throws Exception {
		if (!Files.exists(path)) return null;
		String pem = Files.readString(path, StandardCharsets.UTF_8);
		String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\s", "");
		byte[] der = Base64.getMimeDecoder().decode(base64);
		for (String algorithm : List.of("RSA", "EC", "Ed25519", "DSA")) {
			try {
				return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
			} catch (InvalidKeySpecException notThisKeyType) {
			}
		}
		throw new GeneralSecurityException("Unsupported private key format in " + path);
	}

	/**
	 * A matching pair is left alone. A certificate without a key refuses, because clients may already pin it. A key
	 * without a certificate keeps the key and gets a fresh self-signed certificate built from it, so an operator's key
	 * is never replaced. A fresh pair writes the key first, so a crash in between leaves that recoverable shape.
	 */
	public static void ensureSelfSignedTlsPair(Path certFile, Path privateKeyFile) throws IOException {
		boolean certExists = Files.exists(certFile);
		boolean keyExists = Files.exists(privateKeyFile);
		if (certExists && !keyExists)
			throw new IllegalStateException("The TLS certificate exists without a private key (" + certFile + ", " + privateKeyFile + "); make the pair whole or remove both files manually");
		if (certExists) {
			try {
				X509Certificate cert = loadCertificate(certFile);
				PrivateKey key = loadPrivateKey(privateKeyFile);
				if (cert == null || key == null) throw new GeneralSecurityException("the certificate or the private key is unreadable");
				validateKeyMatchesCertificate(key, cert);
			} catch (Exception e) {
				throw new IllegalStateException("The TLS pair is corrupt (" + certFile + ", " + privateKeyFile + "); fix or remove both files manually - regenerating it would invalidate every client's certificate pin",
						e);
			}
			return;
		}
		KeyPair keyPair;
		try {
			keyPair = keyExists ? existingKeyPair(privateKeyFile) : generateKeyPair();
		} catch (Exception e) {
			throw new IllegalStateException("The TLS private key " + privateKeyFile + " has no certificate and cannot be given a self-signed one; put its certificate at " + certFile, e);
		}
		try {
			if (!keyExists) savePrivateKey(keyPair.getPrivate(), privateKeyFile);
			X509Certificate certificate = selfSign(keyPair);
			saveCertificate(certificate, certFile);
			if (keyExists)
				LOGGER.warn("Built a new self-signed TLS certificate {} for the existing key {}; its fingerprint {} is new, so clients that trusted this server will be asked to trust it again", certFile, privateKeyFile,
						getFingerprint(certificate));
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to write the TLS pair", e);
		}
	}

	/** The pair behind an existing PKCS#8 key: RSA's CRT form carries the public exponent and an EC public point is d·G, so neither needs another file. */
	private static KeyPair existingKeyPair(Path privateKeyFile) throws Exception {
		PrivateKey key = loadPrivateKey(privateKeyFile);
		PublicKey publicKey;
		if (key instanceof RSAPrivateCrtKey rsa) {
			publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
		} else if (key instanceof ECPrivateKey ec && ec.getParams().getCurve().getField() instanceof ECFieldFp field) {
			ECParameterSpec params = ec.getParams();
			BigInteger scalar = ec.getS().mod(params.getOrder());
			if (scalar.signum() == 0) throw new GeneralSecurityException("EC private key scalar is zero");
			ECPoint q = ecMultiply(params.getGenerator(), scalar, params.getCurve().getA(), field.getP());
			if (q == null) throw new GeneralSecurityException("EC public point is at infinity");
			publicKey = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(q, params));
		} else {
			throw new GeneralSecurityException("only an RSA or prime-curve EC private key can sign the self-signed certificate");
		}
		return new KeyPair(publicKey, key);
	}

	/**
	 * {@code scalar · generator} in affine coordinates; null for the point at infinity. Deliberately not constant time:
	 * the scalar is this machine's own key read from disk, the self-signed certificate proves the result against it, and
	 * the loop runs once when a key has no certificate. Do not reuse it on a secret a peer can time.
	 */
	private static ECPoint ecMultiply(ECPoint generator, BigInteger scalar, BigInteger a, BigInteger p) {
		ECPoint result = null;
		ECPoint addend = generator;
		for (int bit = 0; bit < scalar.bitLength(); bit++) {
			if (scalar.testBit(bit)) result = ecAdd(result, addend, a, p);
			addend = ecDouble(addend, a, p);
		}
		return result;
	}

	private static ECPoint ecAdd(ECPoint first, ECPoint second, BigInteger a, BigInteger p) {
		if (first == null) return second;
		if (second == null) return first;
		BigInteger x1 = first.getAffineX();
		BigInteger y1 = first.getAffineY();
		BigInteger x2 = second.getAffineX();
		BigInteger y2 = second.getAffineY();
		if (x1.equals(x2)) return y1.equals(y2) ? ecDouble(first, a, p) : null;
		BigInteger lambda = y2.subtract(y1).multiply(x2.subtract(x1).mod(p).modInverse(p)).mod(p);
		BigInteger x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(p);
		BigInteger y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(p);
		return new ECPoint(x3, y3);
	}

	private static ECPoint ecDouble(ECPoint point, BigInteger a, BigInteger p) {
		if (point == null || point.getAffineY().signum() == 0) return null;
		BigInteger x = point.getAffineX();
		BigInteger y = point.getAffineY();
		BigInteger lambda = x.multiply(x).multiply(THREE).add(a).multiply(y.shiftLeft(1).modInverse(p)).mod(p);
		BigInteger x3 = lambda.multiply(lambda).subtract(x.shiftLeft(1)).mod(p);
		BigInteger y3 = lambda.multiply(x.subtract(x3)).subtract(y).mod(p);
		return new ECPoint(x3, y3);
	}

	/** Proves the key pairs with the certificate by signing a fresh random challenge and verifying it with the certificate's public key. */
	public static void validateKeyMatchesCertificate(PrivateKey privateKey, X509Certificate certificate) throws GeneralSecurityException {
		String algorithm = signatureAlgorithm(privateKey);
		byte[] challenge = new byte[64];
		new SecureRandom().nextBytes(challenge);
		Signature signature = Signature.getInstance(algorithm);
		signature.initSign(privateKey);
		signature.update(challenge);
		Signature verification = Signature.getInstance(algorithm);
		verification.initVerify(certificate.getPublicKey());
		verification.update(challenge);
		if (!verification.verify(signature.sign())) throw new GeneralSecurityException("The private key does not match the certificate " + certificate.getSubjectX500Principal());
	}

	private static String signatureAlgorithm(PrivateKey privateKey) throws GeneralSecurityException {
		return switch (privateKey.getAlgorithm()) {
			case "RSA" -> "SHA256withRSA";
			case "EC", "ECDSA" -> "SHA256withECDSA";
			case "Ed25519" -> "Ed25519";
			case "DSA" -> "SHA256withDSA";
			default -> throw new GeneralSecurityException("Unsupported private key algorithm: " + privateKey.getAlgorithm());
		};
	}

	private static String formatBase64(byte[] derEncodedBytes) {
		Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[]{'\n'});
		return encoder.encodeToString(derEncodedBytes) + "\n";
	}
}
