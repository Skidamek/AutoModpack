package pl.skidam.automodpack_core.protocol;

import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/** The JDK trust manager, then OneCRL: a hit is not a WebPKI vouch. */
public final class RevokingTrustManager extends X509ExtendedTrustManager {

	private final X509ExtendedTrustManager delegate;

	private RevokingTrustManager(X509ExtendedTrustManager delegate) {
		this.delegate = delegate;
	}

	/** The platform CA set when {@code trustStore} is null; tests inject a keystore. */
	public static X509ExtendedTrustManager wrap(KeyStore trustStore) throws KeyStoreException {
		try {
			TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			factory.init(trustStore);
			for (TrustManager manager : factory.getTrustManagers()) {
				if (manager instanceof X509ExtendedTrustManager extended) return new RevokingTrustManager(extended);
			}
			throw new IllegalStateException("No X509ExtendedTrustManager found");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("Default algorithm unavailable", e);
		}
	}

	@Override
	public X509Certificate[] getAcceptedIssuers() {
		return delegate.getAcceptedIssuers();
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		delegate.checkServerTrusted(chain, authType);
		rejectBlocked(chain);
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
		delegate.checkServerTrusted(chain, authType, socket);
		rejectBlocked(chain);
	}

	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
		delegate.checkServerTrusted(chain, authType, engine);
		rejectBlocked(chain);
	}

	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		delegate.checkClientTrusted(chain, authType);
	}

	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
		delegate.checkClientTrusted(chain, authType, socket);
	}

	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
		delegate.checkClientTrusted(chain, authType, engine);
	}

	private static void rejectBlocked(X509Certificate[] chain) throws CertificateException {
		if (OneCrl.blocks(chain)) throw new CertificateException("Certificate is blocked by OneCRL");
	}
}
