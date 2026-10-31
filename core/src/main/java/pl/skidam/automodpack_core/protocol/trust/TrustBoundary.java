package pl.skidam.automodpack_core.protocol.trust;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.X509ExtendedTrustManager;

/**
 * The trust decision. It is loaded by its own loader from this jar (see {@code protocol.Trust}), whose parent is the
 * platform loader, so it resolves only the JDK and this package: no game, loader or mod class is on its path, and its
 * own class loading never reaches the game's transformer. Every method takes and returns JDK types only, because any
 * type defined here is a second copy of the same name on the other side.
 */
public final class TrustBoundary {
	private static final System.Logger LOGGER = System.getLogger("AutoModpackTrust");
	private static final AtomicBoolean KEYING_FAILED = new AtomicBoolean();
	private static volatile boolean started;
	private static volatile Set<String> baseIssuer = Set.of();
	private static volatile Set<String> baseSpki = Set.of();
	private static volatile Set<String> feedIssuer = Set.of();
	private static volatile Set<String> feedSpki = Set.of();

	private TrustBoundary() {}

	/** The bundled floor. Loading it is what marks the boundary ready to decide; an empty floor is a broken launch. */
	public static void snapshot(Set<String> issuerSerial, Set<String> spkiHex) {
		Set<String> issuers = Set.copyOf(issuerSerial);
		if (issuers.isEmpty()) throw new IllegalStateException("The bundled OneCRL snapshot is empty");
		baseIssuer = issuers;
		baseSpki = Set.copyOf(spkiHex);
		started = true;
	}

	/** The live Mozilla feed, overlaid on the floor; an empty feed leaves the floor in place. */
	public static void feed(Set<String> issuerSerial, Set<String> spkiHex) {
		feedIssuer = Set.copyOf(issuerSerial);
		feedSpki = Set.copyOf(spkiHex);
	}

	/** True when the certificate is on the floor or the feed. A certificate that cannot be keyed fails closed, once. */
	public static boolean blocks(X509Certificate certificate) {
		if (!started) throw new IllegalStateException("The trust boundary decided before its OneCRL snapshot was loaded");
		try {
			String spki = spkiHex(certificate);
			if (baseSpki.contains(spki) || feedSpki.contains(spki)) return true;
			String key = issuerSerialKey(certificate);
			return baseIssuer.contains(key) || feedIssuer.contains(key);
		} catch (RuntimeException unkeyable) {
			if (KEYING_FAILED.compareAndSet(false, true)) {
				LOGGER.log(System.Logger.Level.WARNING, "Could not key a certificate for OneCRL and refused it; logged once per process", unkeyable);
			}
			return true;
		}
	}

	/** {@code issuerDER|serial} as hex, the key a Mozilla record and a certificate share. */
	public static String key(byte[] issuerDer, byte[] serial) {
		return HexFormat.of().formatHex(issuerDer) + "|" + HexFormat.of().formatHex(serial);
	}

	static String issuerSerialKey(X509Certificate certificate) {
		return key(certificate.getIssuerX500Principal().getEncoded(), certificate.getSerialNumber().toByteArray());
	}

	private static String spkiHex(X509Certificate certificate) {
		byte[] encoded = certificate.getPublicKey().getEncoded();
		if (encoded == null) return "";
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/** The platform CA set when {@code trustStore} is null; tests inject a keystore. */
	public static X509ExtendedTrustManager trustManager(KeyStore trustStore) throws KeyStoreException {
		return RevokingTrustManager.wrap(trustStore);
	}

	/** The leaf's http CRL distribution point, or null when it names none. */
	public static String crlUrl(X509Certificate leaf) {
		try {
			return CrlVerdict.httpUrl(leaf);
		} catch (RuntimeException unreadable) {
			throw new IllegalArgumentException("Cannot read the certificate's CRL distribution points", unreadable);
		}
	}

	/**
	 * The verdict ints are a wire format between the two sides of the boundary, which cannot share a type:
	 * {@code 0} unusable body, {@code 1} good, {@code 2} revoked, {@code 3} unknown.
	 */
	public static int crlVerdict(X509Certificate leaf, X509Certificate[] presented, byte[] body, byte[] cachedBody) {
		return CrlVerdict.verdict(leaf, presented, body, cachedBody);
	}
}
