package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.AM_VERSION;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HexFormat;
import java.util.Locale;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;

public class NetUtils {
	public static final String USER_AGENT = "github/skidamek/automodpack/" + AM_VERSION;
	public static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(15);
	// The configured-connection read deadline also guards bulk file transfers, where legitimate
	// flow-control pauses outlast a connect-grade deadline. It only has to catch a dead peer, not
	// a slow pipe, so it sits far past any healthy inter-frame gap.
	public static final Duration TRANSFER_IDLE_TIMEOUT = Duration.ofSeconds(60);
	// The transfer write-stall tripwire: how long a frame may sit on the socket with zero drain
	// progress before the peer is declared gone. Only a peer that stopped reading entirely can trip
	// it - a live link resets the window with every drained byte - and a genuinely dead peer
	// surfaces faster through its own 60 s read deadline closing the socket. 90 s is 1.5x that
	// window, so the stall fuse is never the first thing to fire on a healthy connection.
	public static final Duration TRANSFER_WRITE_STALL_TIMEOUT = Duration.ofSeconds(90);
	// Per-connection concurrent file transfer tripwire: an honest client pipelines exactly one
	// request per connection, so this sits 4x past any good component and only a broken or hostile
	// one touches it. It bounds the sender workers (one thread plus ~16 MiB of buffers each) a
	// single authenticated connection can pin with pipelined file requests.
	public static final int MAX_CONCURRENT_TRANSFERS_PER_CONNECTION = 4;
	// The pre-configuration lifetime tripwire: while the human decides on certificate trust the server must never reap
	// the socket for idleness, so pre-configuration sockets are bounded only by this one window. It sits an order of
	// magnitude past the transfer idle deadline (60 s) and far past the connect-grade network timeout (15 s) - generous
	// for a slow human reading the fingerprint plus a slow first TLS handshake, tight enough that a client which died at
	// the screen cannot pin a server socket forever.
	public static final Duration PRE_CONFIGURATION_LIFETIME = Duration.ofMinutes(10);
	// Pre-configuration keepalive cadence: NAT mappings and holepunch relay bindings typically decay after 30-60s of
	// silence, so a 20s heartbeat sits well inside that band while costing the parked client one two-byte write.
	public static final Duration PRE_CONFIGURATION_KEEPALIVE_INTERVAL = Duration.ofSeconds(20);
	// The configured-but-unauthenticated lifetime: every honest client sends its secret in its first protocol message,
	// so the honest gap between configuration and authentication is machine-speed, and any byte sent after configuration
	// either authenticates or closes the connection - the deadline cannot be stretched. 60s sits two orders of magnitude
	// past that gap while bounding how long an unauthenticated peer can pin a host socket.
	public static final Duration UNAUTHENTICATED_LIFETIME = Duration.ofSeconds(60);
	// The authenticated all-idle bound: transfers and requests reset it continuously and an honest human pause between
	// negotiation and confirmation fits inside it with room to spare, so only a zombie holding a revoked or leaked
	// secret pays it - at the cost of one reconnect for a player who walks away for over an hour mid-review.
	public static final Duration AUTHENTICATED_IDLE_TIMEOUT = Duration.ofHours(1);
	public static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
	public static final int NETWORK_TIMEOUT_MILLIS = Math.toIntExact(NETWORK_TIMEOUT.toMillis());
	public static final int TRANSFER_IDLE_TIMEOUT_MILLIS = Math.toIntExact(TRANSFER_IDLE_TIMEOUT.toMillis());
	public static final int HTTP_TIMEOUT_MILLIS = Math.toIntExact(HTTP_TIMEOUT.toMillis());

	public static final int MAGIC_AMMH = 0x414D4D48;
	public static final int MAGIC_AMOK = 0x414D4F4B;

	public static final byte LATEST_SUPPORTED_PROTOCOL_VERSION = 0x02;

	// Message types and configuration message types should not overlap
	public static final byte ECHO_TYPE = 0x00;
	public static final byte FILE_REQUEST_TYPE = 0x01;
	public static final byte FILE_RESPONSE_TYPE = 0x02;
	// Answer to a conditional document request whose expected hash still matches the served document; documents only, objects never.
	public static final byte UNCHANGED_TYPE = 0x03;
	public static final byte END_OF_TRANSMISSION = 0x04;
	public static final byte ERROR = 0x05;

	// Machine-readable ERROR codes, the trailing byte of every ERROR frame this protocol version writes. The message
	// stays for logs and humans; the code is what a client may branch on without parsing prose.
	public static final byte ERROR_CODE_GENERIC = 0x00;
	public static final byte ERROR_CODE_STALE_RANGE = 0x01;

	// FILE_REQUEST trailing extension flags (protocol 0x02): a set bit means the field follows the flags byte, in bit order; the end offset requires the range offset.
	public static final byte FILE_REQUEST_EXPECTED_SHA1_FLAG = 0x01;
	public static final byte FILE_REQUEST_OFFSET_FLAG = 0x02;
	public static final byte FILE_REQUEST_END_FLAG = 0x04;
	public static final byte FILE_REQUEST_KNOWN_FLAGS = FILE_REQUEST_EXPECTED_SHA1_FLAG | FILE_REQUEST_OFFSET_FLAG | FILE_REQUEST_END_FLAG;

	public static final byte CONFIGURATION_ECHO_TYPE = 0x40;
	public static final byte CONFIGURATION_COMPRESSION_TYPE = 0x41;
	public static final byte CONFIGURATION_CHUNK_SIZE_TYPE = 0x42;
	// A client parked on its certificate-trust decision heartbeats these; the server absorbs them silently.
	public static final byte CONFIGURATION_KEEPALIVE_TYPE = 0x4F;

	public static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; // 4 MiB
	public static final int MIN_CHUNK_SIZE = 1024 * 1024; // 1 MiB
	public static final int MAX_CHUNK_SIZE = 8 * 1024 * 1024; // 8 MiB

	// Protocol message field tripwires. The decoder reads these lengths pre-authentication, so they must
	// never trust a client length near the buffer sizes: an honest echo carries a small nonce and an
	// honest file request carries one hex SHA-1, so both caps sit an order of magnitude past any good
	// client while staying thousands of bytes below one frame.
	public static final int MAX_ECHO_PAYLOAD_BYTES = 1024;
	public static final int MAX_FILE_HASH_BYTES = 128;

	private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
	private static final AlgorithmIdentifier SIGNATURE_ALGORITHM_IDENTIFIER = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);

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

	public static String normalizeFingerprint(String fingerprint) {
		String normalized = fingerprint == null ? "" : fingerprint.replace(":", "").trim().toLowerCase(Locale.ROOT);
		if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Certificate fingerprint must be 64 hexadecimal characters");
		return normalized;
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

	public static X509Certificate selfSign(KeyPair keyPair) throws Exception {
		long now = System.currentTimeMillis();
		Date startDate = new Date(now);

		X500Principal distinguishedName = new X500Principal("CN=AutoModpack Self Signed Certificate");
		BigInteger certSerialNumber = new BigInteger(159, new SecureRandom());

		Calendar calendar = Calendar.getInstance();
		calendar.setTime(startDate);
		calendar.add(Calendar.YEAR, 1);
		Date endDate = calendar.getTime();

		ContentSigner contentSigner = createContentSigner(keyPair.getPrivate());
		JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(distinguishedName, certSerialNumber, startDate, endDate, distinguishedName, keyPair.getPublic());

		byte[] encodedCertificate = certBuilder.build(contentSigner).getEncoded();
		X509Certificate certificate;
		try (InputStream input = new ByteArrayInputStream(encodedCertificate)) {
			certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
		}
		certificate.verify(keyPair.getPublic());
		return certificate;
	}

	private static ContentSigner createContentSigner(PrivateKey privateKey) throws GeneralSecurityException {
		Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
		signature.initSign(privateKey);
		ByteArrayOutputStream encodedCertificate = new ByteArrayOutputStream();

		return new ContentSigner() {
			@Override
			public AlgorithmIdentifier getAlgorithmIdentifier() {
				return SIGNATURE_ALGORITHM_IDENTIFIER;
			}

			@Override
			public OutputStream getOutputStream() {
				return encodedCertificate;
			}

			@Override
			public byte[] getSignature() {
				try {
					signature.update(encodedCertificate.toByteArray());
					return signature.sign();
				} catch (SignatureException e) {
					throw new IllegalStateException("Failed to sign certificate", e);
				}
			}
		};
	}

	public static void saveCertificate(X509Certificate cert, Path path) throws Exception {
		String certPem = "-----BEGIN CERTIFICATE-----\n" + formatBase64(cert.getEncoded()) + "-----END CERTIFICATE-----\n";
		if (path.getParent() != null) Files.createDirectories(path.getParent());
		Files.writeString(path, certPem, StandardCharsets.UTF_8);
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
		if (path.getParent() != null) Files.createDirectories(path.getParent());
		Files.writeString(path, keyPem, StandardCharsets.UTF_8);
	}

	private static String formatBase64(byte[] derEncodedBytes) {
		Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[]{'\n'});
		return encoder.encodeToString(derEncodedBytes) + "\n";
	}
}
