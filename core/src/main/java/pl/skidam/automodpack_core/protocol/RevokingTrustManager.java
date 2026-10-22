package pl.skidam.automodpack_core.protocol;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * The JDK trust manager, then OneCRL over the certification paths to a trust anchor, which may run through trust-store
 * certificates the server never sent: a path only through blocked certificates is not a WebPKI vouch.
 */
public final class RevokingTrustManager extends X509ExtendedTrustManager {

	private final X509ExtendedTrustManager delegate;
	private final Set<TrustAnchor> anchors;

	private RevokingTrustManager(X509ExtendedTrustManager delegate) {
		this.delegate = delegate;
		this.anchors = Arrays.stream(delegate.getAcceptedIssuers()).map(certificate -> new TrustAnchor(certificate, null)).collect(Collectors.toUnmodifiableSet());
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

	/**
	 * OneCRL judges certification paths, never certificates a path does not use, the way Firefox does: the handshake
	 * stands while one path from the leaf to a trust anchor runs through no blocked certificate, trust-store ones the
	 * server never sent included. The builder is consulted only when a blocked certificate is anywhere a path could run.
	 */
	private void rejectBlocked(X509Certificate[] chain) throws CertificateException {
		List<X509Certificate> presented = Arrays.stream(chain).filter(certificate -> !OneCrl.blocks(certificate)).toList();
		Set<TrustAnchor> clean = anchors.stream().filter(anchor -> !OneCrl.blocks(anchor.getTrustedCert())).collect(Collectors.toUnmodifiableSet());
		if (presented.size() == chain.length && clean.size() == anchors.size()) return;
		if (presented.isEmpty() || presented.get(0) != chain[0]) throw blocked();
		if (builds(chain[0], presented, clean)) return;
		if (builds(chain[0], List.of(chain), anchors)) throw blocked();
		// The builder cannot reproduce the path the JDK accepted, for a reason that is not OneCRL's: judge the issuer chains
		// that path can have been, never a presented certificate none of them uses.
		if (!cleanIssuerChain(chain[0], List.of(chain))) throw blocked();
	}

	/**
	 * Whether {@code leaf} reaches a trust anchor through issuers it is linked to by name and signature, with no blocked
	 * certificate on the way: those are the only chains the JDK can have accepted it through. Whether a certificate is
	 * blocked and which certificates issued it never depend on the chain below it, so each is searched once and a chain
	 * padded with certificates cross-signing each other costs no more than its size.
	 */
	private boolean cleanIssuerChain(X509Certificate leaf, List<X509Certificate> presented) {
		Deque<X509Certificate> pending = new ArrayDeque<>(List.of(leaf));
		Set<X509Certificate> seen = new HashSet<>(pending);
		while (!pending.isEmpty()) {
			X509Certificate certificate = pending.pop();
			if (OneCrl.blocks(certificate)) continue;
			for (TrustAnchor anchor : anchors)
				if (anchor.getTrustedCert().equals(certificate) || issued(anchor.getTrustedCert(), certificate) && !OneCrl.blocks(anchor.getTrustedCert())) return true;
			for (X509Certificate issuer : presented)
				if (!seen.contains(issuer) && issued(issuer, certificate)) {
					seen.add(issuer);
					pending.push(issuer);
				}
		}
		return false;
	}

	private static boolean issued(X509Certificate issuer, X509Certificate certificate) {
		if (!issuer.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) return false;
		try {
			certificate.verify(issuer.getPublicKey());
			return true;
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	/** Whether a PKIX path runs from the leaf through {@code certificates} to one of {@code trusted}; revocation stays off, the JDK and OneCRL own it. */
	private static boolean builds(X509Certificate leaf, List<X509Certificate> certificates, Set<TrustAnchor> trusted) {
		for (TrustAnchor anchor : trusted)
			if (anchor.getTrustedCert().equals(leaf)) return true;
		try {
			X509CertSelector target = new X509CertSelector();
			target.setCertificate(leaf);
			PKIXBuilderParameters parameters = new PKIXBuilderParameters(trusted, target);
			parameters.setRevocationEnabled(false);
			parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(certificates)));
			CertPathBuilder.getInstance("PKIX").build(parameters);
			return true;
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	private static CertificateException blocked() {
		return new CertificateException("Certificate is blocked by OneCRL");
	}
}
