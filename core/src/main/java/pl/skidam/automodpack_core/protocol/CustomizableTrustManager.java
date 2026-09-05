package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.getFingerprint;
import static pl.skidam.automodpack_core.protocol.NetUtils.normalizeFingerprint;

import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

public class CustomizableTrustManager extends X509ExtendedTrustManager {

	public static final class SessionTrust {
		private final String origin;
		private final String configuredFingerprint;
		private final AtomicReference<String> acceptedFingerprint = new AtomicReference<>();

		public SessionTrust(String origin, String configuredFingerprint) {
			this.origin = origin;
			this.configuredFingerprint = configuredFingerprint == null ? null : normalizeFingerprint(configuredFingerprint);
		}

		boolean checkPin(X509Certificate[] chain) throws CertificateException {
			String expected = configuredFingerprint != null ? configuredFingerprint : acceptedFingerprint.get();
			if (expected == null) return false;
			if (chain == null || chain.length == 0) throw new CertificateException("Server did not present a certificate");

			String presented = getFingerprint(chain[0]);
			if (!expected.equals(presented)) throw new CertificatePinMismatchException(origin, expected, presented);
			return true;
		}

		boolean hasAccepted() {
			return acceptedFingerprint.get() != null || configuredFingerprint != null;
		}

		void accept(X509Certificate certificate) throws CertificateException {
			String fingerprint = getFingerprint(certificate);
			String expected = configuredFingerprint;
			if (expected != null && !expected.equals(fingerprint)) throw new CertificatePinMismatchException(origin, expected, fingerprint);

			String previous = acceptedFingerprint.get();
			if (previous != null && !previous.equals(fingerprint)) throw new CertificatePinMismatchException(origin, previous, fingerprint);
			acceptedFingerprint.compareAndSet(null, fingerprint);
		}
	}

	private final X509ExtendedTrustManager defaultTrustManager;
	private final SessionTrust sessionTrust;
	private final Consumer<X509Certificate[]> onValidating;
	private volatile X509Certificate deferredCertificate;
	private volatile CertificateException deferredFailure;

	public CustomizableTrustManager(SessionTrust sessionTrust, Consumer<X509Certificate[]> onValidating) throws KeyStoreException {
		this.defaultTrustManager = createTrustManager();
		this.sessionTrust = sessionTrust;
		this.onValidating = onValidating;
	}

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

	public X509Certificate getDeferredCertificate() {
		return deferredCertificate;
	}

	public CertificateException getDeferredFailure() {
		return deferredFailure;
	}

	@Override
	public X509Certificate[] getAcceptedIssuers() {
		return defaultTrustManager.getAcceptedIssuers();
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		validateServer(chain, () -> defaultTrustManager.checkServerTrusted(chain, authType));
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
		validateServer(chain, () -> defaultTrustManager.checkServerTrusted(chain, authType, socket));
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
		validateServer(chain, () -> defaultTrustManager.checkServerTrusted(chain, authType, engine));
	}

	private void validateServer(X509Certificate[] chain, TrustCheck defaultCheck) throws CertificateException {
		if (onValidating != null) onValidating.accept(chain);
		if (sessionTrust.checkPin(chain)) return;

		try {
			defaultCheck.check();
		} catch (CertificateException e) {
			if (chain == null || chain.length == 0 || !DownloadClient.isSelfSigned(chain[0])) throw e;
			deferredCertificate = chain[0];
			deferredFailure = e;
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
