package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.getFingerprint;
import static pl.skidam.automodpack_core.protocol.NetUtils.normalizeFingerprint;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

public class CustomizableTrustManager extends X509ExtendedTrustManager {

	public static final class SessionTrust {
		private final String origin;
		private volatile String configuredFingerprint;
		private final AtomicReference<String> acceptedFingerprint = new AtomicReference<>();

		public SessionTrust(String origin, String configuredFingerprint) {
			this.origin = origin;
			this.configuredFingerprint = configuredFingerprint == null ? null : normalizeFingerprint(configuredFingerprint);
		}

		/** True when this leaf is exactly the pin this session trusts: the configured pin or one accepted earlier in the session. */
		boolean pinMatches(X509Certificate[] chain) throws CertificateEncodingException {
			String expected = expectedFingerprint();
			if (expected == null || chain == null || chain.length == 0) return false;
			return expected.equals(getFingerprint(chain[0]));
		}

		/** Whether a pin was configured for this origin; a pinned session whose leaf changed has no bypass, only recovery channels. */
		boolean hasConfiguredPin() {
			return configuredFingerprint != null;
		}

		/** The mismatch a deferred certificate fails with; the ladder surfaces it, the handshake itself stays quiet. */
		CertificatePinMismatchException mismatch(X509Certificate leaf) throws CertificateEncodingException {
			return new CertificatePinMismatchException(origin, expectedFingerprint(), leaf == null ? null : getFingerprint(leaf));
		}

		void accept(X509Certificate certificate) throws CertificateException {
			String fingerprint = getFingerprint(certificate);
			String expected = configuredFingerprint;
			if (expected != null && !expected.equals(fingerprint)) throw new CertificatePinMismatchException(origin, expected, fingerprint);

			String previous = acceptedFingerprint.get();
			if (previous != null && !previous.equals(fingerprint)) throw new CertificatePinMismatchException(origin, previous, fingerprint);
			// Two lanes can pass the guard together; a CAS loser must not walk away accepted while the pin holds the other certificate.
			if (!acceptedFingerprint.compareAndSet(null, fingerprint) && !fingerprint.equals(acceptedFingerprint.get()))
				throw new CertificatePinMismatchException(origin, acceptedFingerprint.get(), fingerprint);
		}

		/** A published DNSSEC fingerprint approved this leaf (typically rotation): the session's pin follows it. */
		void recover(X509Certificate certificate) throws CertificateException {
			String fingerprint = getFingerprint(certificate);
			configuredFingerprint = fingerprint;
			acceptedFingerprint.set(fingerprint);
		}

		private String expectedFingerprint() {
			return configuredFingerprint != null ? configuredFingerprint : acceptedFingerprint.get();
		}
	}

	private final X509ExtendedTrustManager defaultTrustManager;
	private final SessionTrust sessionTrust;
	private final Consumer<X509Certificate[]> onValidating;
	// The deferred trust state is keyed per peer, not per handshake, so one manager serves every lane of a client:
	// concurrent handshakes defer into their own slots and the trust ladder reads exactly the certificate each
	// candidate socket presented. Keyed by Socket (the SSLSocket variant the JDK calls for our handshakes), by
	// SSLEngine for engine handshakes, and by null for the bare checkServerTrusted(chain, authType) variant; an entry
	// is spent by CandidateTrustValidation when the ladder has judged its socket, pass or fail.
	private final Map<Object, Deferred> deferred = new HashMap<>();

	public CustomizableTrustManager(SessionTrust sessionTrust, Consumer<X509Certificate[]> onValidating) throws KeyStoreException {
		this.defaultTrustManager = createTrustManager();
		this.sessionTrust = sessionTrust;
		this.onValidating = onValidating;
	}

	private record Deferred(X509Certificate certificate, CertificateException failure) {}

	private static X509ExtendedTrustManager createTrustManager() throws KeyStoreException {
		try {
			TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			factory.init((KeyStore) null);
			for (TrustManager manager : factory.getTrustManagers()) {
				if (manager instanceof X509ExtendedTrustManager extended) return extended;
			}
			throw new IllegalStateException("No X509ExtendedTrustManager found");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("Default algorithm unavailable", e);
		}
	}

	public synchronized X509Certificate getDeferredCertificate(Socket socket) {
		Deferred entry = deferred.get(socket);
		return entry == null ? null : entry.certificate();
	}

	public synchronized CertificateException getDeferredFailure(Socket socket) {
		Deferred entry = deferred.get(socket);
		return entry == null ? null : entry.failure();
	}

	/** The ladder is done with this socket's deferral, pass or fail: its entry is spent and freed. */
	public synchronized void forget(Socket socket) {
		deferred.remove(socket);
	}

	@Override
	public X509Certificate[] getAcceptedIssuers() {
		return defaultTrustManager.getAcceptedIssuers();
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		validateServer(null, chain, () -> defaultTrustManager.checkServerTrusted(chain, authType));
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
		validateServer(socket, chain, () -> defaultTrustManager.checkServerTrusted(chain, authType, socket));
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
		validateServer(engine, chain, () -> defaultTrustManager.checkServerTrusted(chain, authType, engine));
	}

	private void validateServer(Object peer, X509Certificate[] chain, TrustCheck defaultCheck) throws CertificateException {
		if (onValidating != null) onValidating.accept(chain);
		if (chain == null || chain.length == 0) throw new CertificateException("Server did not present a certificate");
		if (sessionTrust.pinMatches(chain)) return;

		try {
			defaultCheck.check();
		} catch (CertificateException e) {
			// A chain the CAs reject is only deferrable when it is genuinely self-signed; anything else is broken.
			if (!isSelfSigned(chain[0])) throw e;
			synchronized (this) {
				deferred.put(peer, new Deferred(chain[0], e));
			}
			return;
		}
		// No pin matched this leaf: a first contact (whatever the CA said) or a changed certificate. The ladder
		// decides - a published DNSSEC fingerprint speaks for the endpoint, and a first contact is the player's.
		synchronized (this) {
			deferred.put(peer, new Deferred(chain[0], null));
		}
	}

	/** A certificate genuinely signed by its own key, not merely one whose subject equals its issuer. */
	static boolean isSelfSigned(X509Certificate certificate) {
		if (certificate == null || !certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) return false;

		try {
			certificate.verify(certificate.getPublicKey());
			return true;
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		defaultTrustManager.checkClientTrusted(chain, authType);
	}

	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
		defaultTrustManager.checkClientTrusted(chain, authType, socket);
	}

	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
		defaultTrustManager.checkClientTrusted(chain, authType, engine);
	}

	@FunctionalInterface
	private interface TrustCheck {
		void check() throws CertificateException;
	}
}
