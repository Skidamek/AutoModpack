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
	// The idle reap for public contract connections, in seconds of no reads and no writes. It sits far past any
	// client's keep-alive reuse window while staying inside a minute-scale patience for silent sockets; a streaming
	// response writes continuously, so the reap can never interrupt a live transfer.
	public static final int HTTP_IDLE_REAP_SECONDS = 60;
	// Pre-configuration keepalive cadence: NAT mappings and holepunch relay bindings typically decay after 30-60s of
	// silence, so a 20s heartbeat sits well inside that band while costing the parked client one tiny ranged GET.
	public static final Duration PRE_CONFIGURATION_KEEPALIVE_INTERVAL = Duration.ofSeconds(20);
	public static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
	public static final int NETWORK_TIMEOUT_MILLIS = Math.toIntExact(NETWORK_TIMEOUT.toMillis());
	public static final int TRANSFER_IDLE_TIMEOUT_MILLIS = Math.toIntExact(TRANSFER_IDLE_TIMEOUT.toMillis());
	public static final int HTTP_TIMEOUT_MILLIS = Math.toIntExact(HTTP_TIMEOUT.toMillis());

	public static final int MAGIC_AMMH = 0x414D4D48;
	public static final int MAGIC_AMOK = 0x414D4F4B;

	// The body chunk both ends stream in; the client reads one buffer of it per syscall loop and the server streams file bodies through it.
	public static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; // 4 MiB

	// The server queues whole response chunks ahead of the peer's drain instead of stop-and-wait: writing resumes below
	// the low watermark, pauses at the high one, so compression overlaps the wire and memory stays bounded - one identity
	// chunk of queued response per connection, ≈5 lanes × 4 MiB = 20 MiB worst case across a full pool.
	public static final int WRITE_BUFFER_LOW_WATER = 512 * 1024;
	public static final int WRITE_BUFFER_HIGH_WATER = 4 * 1024 * 1024; // 4 MiB

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
