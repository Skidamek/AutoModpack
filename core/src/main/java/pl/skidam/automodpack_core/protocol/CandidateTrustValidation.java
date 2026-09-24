package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.closeQuietly;
import static pl.skidam.automodpack_core.protocol.NetUtils.getFingerprint;

import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import pl.skidam.automodpack_core.auth.DnsPinResolver;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.Throwables;

/**
 * The one certificate-trust plumbing and ladder every client transport runs over a freshly handshaked candidate
 * socket. The ladder reads, in order: a saved pin is law - the presented leaf must match it exactly, and a changed
 * leaf fails outright with no recovery short of the player revoking the pin or importing a new pinned join address;
 * a published DNSSEC fingerprint for the typed hostname accepts the leaf and is never stored; a CA chain that also
 * covers the typed hostname accepts the leaf and is never stored; and whatever remains is the player's explicit
 * decision, heartbeated while the human decides. On acceptance the session trust pins the certificate, so every
 * later handshake on the same SSLContext passes without asking again.
 */
public final class CandidateTrustValidation {

	/** One daemon thread heartbeats every candidate parked on a certificate-trust decision. */
	private static final ScheduledExecutorService PRE_CONFIGURATION_KEEPALIVE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(
			new CustomThreadFactoryBuilder().setNameFormat("AutoModpack PreConfigurationKeepalive #%d").setDaemon(true).build());

	/** One transport candidate: its probe socket, the client's shared trust manager holding that socket's deferral, and who may accept the certificate. */
	public record Candidate(SSLSocket socket, CustomizableTrustManager trustManager, CustomizableTrustManager.SessionTrust sessionTrust, String originHost, String endpointHost,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback, BooleanSupplier clientAlive, String hostHeader, String secret) {}

	private CandidateTrustValidation() {}

	/** An SSL context that trusts exactly the given manager, so one accepted pin covers every later handshake. */
	public static SSLContext newSslContext(CustomizableTrustManager trustManager) {
		try {
			SSLContext context = SSLContext.getInstance("TLSv1.3");
			context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
			return context;
		} catch (NoSuchAlgorithmException | KeyManagementException e) {
			throw new RuntimeException("Failed to initialize SSLContext", e);
		}
	}

	/** The one TLS shape both transports speak: TLSv1.3, the AEAD cipher list, and endpoint identification over the pinned session. */
	public static SSLSocket wrapWithTls(Socket plainSocket, SSLContext context, String endpointHost, int endpointPort) throws IOException {
		SSLSocketFactory factory = context.getSocketFactory();
		SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, endpointHost, endpointPort, true);
		sslSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
		sslSocket.setEnabledCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"});

		SSLParameters parameters = new SSLParameters();
		parameters.setEndpointIdentificationAlgorithm("HTTPS");
		sslSocket.setSSLParameters(parameters);

		try {
			sslSocket.startHandshake();
			return sslSocket;
		} catch (IOException e) {
			closeQuietly(sslSocket);
			throw e;
		}
	}

	/** Runs the ladder over the candidate; completion means the certificate is pinned into the session trust, and any failure closes the probe socket. */
	public static CompletableFuture<Void> validate(Candidate candidate, Duration preConfigurationKeepaliveInterval) {
		CustomizableTrustManager trustManager = candidate.trustManager();
		CustomizableTrustManager.SessionTrust sessionTrust = candidate.sessionTrust();

		// A resumed handshake never enters the trust manager, so it has no deferral: its certificate comes from the
		// cached session, and it may only be the pin or the leaf the session already accepted. Anything else is a
		// session the ladder condemned (or has not accepted yet); it is invalidated and dropped, never laundered.
		if (trustManager.getDeferredCertificate(candidate.socket()) == null) {
			X509Certificate[] resumed = resumedChain(candidate);
			boolean acceptable = false;
			if (resumed != null) {
				try {
					acceptable = sessionTrust.pinMatches(resumed) || sessionTrust.acceptedMatches(resumed);
				} catch (CertificateEncodingException e) {
					acceptable = false;
				}
			}
			if (!acceptable) {
				candidate.socket().getSession().invalidate();
				closeQuietly(candidate.socket());
				return CompletableFuture.failedFuture(new IOException("Resumed a TLS session the trust ladder did not accept"));
			}
			return CompletableFuture.completedFuture(null);
		}

		// A pin-matched handshake is done: the pin is law and nothing else runs.
		if (trustManager.isPinMatched(candidate.socket())) return CompletableFuture.completedFuture(null);

		X509Certificate certificate = trustManager.getDeferredCertificate(candidate.socket());

		CompletableFuture<Void> validation;
		try {
			certificate.checkValidity();
			validation = judge(candidate, certificate, preConfigurationKeepaliveInterval);
		} catch (CertificateException e) {
			validation = CompletableFuture.failedFuture(new IOException("Untrusted certificate is not valid", e));
		}
		return validation.whenComplete((ignored, error) -> {
			// The deferral is spent once the ladder has judged this socket, pass or fail; the entry dies with the decision.
			candidate.trustManager().forget(candidate.socket());
			if (error != null) closeQuietly(candidate.socket());
		});
	}

	/**
	 * The trust ladder over one deferred certificate, in order: a pinned origin whose leaf changed fails outright -
	 * a pin is a pin, and no record, CA, or prompt recovers it; a published DNSSEC fingerprint is the operator's
	 * explicit statement and is law when present; a CA chain that covers the typed hostname is the WebPKI's vouch
	 * for the address the player typed; and whatever remains is the player's decision.
	 */
	private static CompletableFuture<Void> judge(Candidate candidate, X509Certificate certificate, Duration preConfigurationKeepaliveInterval) {
		if (candidate.sessionTrust().hasConfiguredPin()) {
			// The leaf already differed from the pin when the handshake deferred it: the mismatch is final.
			try {
				return reject(candidate, candidate.sessionTrust().mismatch(certificate));
			} catch (CertificateEncodingException e) {
				return reject(candidate, new IOException("Cannot fingerprint the deferred certificate", e));
			}
		}
		return DnsPinResolver.resolvePinAsync(candidate.originHost()).thenCompose(result -> {
			if (result instanceof DnsPinResolver.Authoritative authoritative) {
				try {
					String fingerprint = getFingerprint(certificate);
					if (!authoritative.fingerprint().equals(fingerprint)) return reject(candidate, candidate.sessionTrust().mismatch(certificate));
					candidate.sessionTrust().accept(certificate);
					LOGGER.info("Trusting the certificate from {} because it matches the published DNSSEC fingerprint for {}", candidate.endpointHost(), candidate.originHost());
					return CompletableFuture.completedFuture(null);
				} catch (CertificateException e) {
					return reject(candidate, new IOException("Failed to validate the DNSSEC-pinned certificate", e));
				}
			}
			if (result instanceof DnsPinResolver.Misconfigured misconfigured) {
				return reject(candidate, new IOException("Invalid DNSSEC AutoModpack fingerprint for " + candidate.originHost() + ": " + misconfigured.reason()));
			}
			// No published fingerprint. A CA chain that passed the JDK's validation for the endpoint also covers
			// the typed origin: the deferral carries no failure exactly when the CAs vouched for this chain, and
			// the origin name in its SANs anchors that vouch to the address the player typed. Self-signed leaves
			// deferred with a failure never take this step.
			if (candidate.trustManager().getDeferredFailure(candidate.socket()) == null && leafCoversOrigin(certificate, candidate.originHost())) {
				try {
					candidate.sessionTrust().accept(certificate);
					LOGGER.info("Trusting the certificate from {} because its CA chain covers the origin {}", candidate.endpointHost(), candidate.originHost());
					return CompletableFuture.completedFuture(null);
				} catch (CertificateException e) {
					return reject(candidate, new IOException("Cannot fingerprint the CA-signed certificate", e));
				}
			}
			// A first contact is the player's decision.
			return requestManualTrust(candidate, certificate, preConfigurationKeepaliveInterval);
		});
	}

	/**
	 * Whether the leaf names the typed origin host among its SAN entries: an exact dNSName, a leftmost wildcard
	 * covering exactly one label, or the literal address for an IP origin. The chain itself was already validated
	 * by the JDK check the deferral captured; this is the second, origin-anchored name the ladder asks of it.
	 */
	static boolean leafCoversOrigin(X509Certificate certificate, String originHost) {
		Collection<List<?>> entries;
		try {
			entries = certificate == null ? null : certificate.getSubjectAlternativeNames();
		} catch (CertificateParsingException e) {
			return false;
		}
		if (entries == null) return false;
		for (List<?> entry : entries) {
			if (entry == null || entry.size() < 2 || !(entry.get(0) instanceof Integer nameType)) continue;
			Object value = entry.get(1);
			if (nameType == 2 && value instanceof String name && nameCoversOrigin(name, originHost)) return true;
			if (nameType == 7 && value instanceof String address && address.equalsIgnoreCase(originHost)) return true;
		}
		return false;
	}

	/** RFC 6125 identity matching: the whole name, or a wildcard in its leftmost label covering exactly one label. */
	private static boolean nameCoversOrigin(String name, String originHost) {
		String san = name.toLowerCase(Locale.ROOT);
		String origin = originHost.toLowerCase(Locale.ROOT);
		if (!san.startsWith("*.")) return san.equals(origin);
		String remainder = san.substring(1); // ".example.com"
		return origin.endsWith(remainder) && origin.indexOf('.') == origin.length() - remainder.length() && origin.length() > remainder.length();
	}

	private static CompletableFuture<Void> requestManualTrust(Candidate candidate, X509Certificate certificate, Duration preConfigurationKeepaliveInterval) {
		if (candidate.trustCallback() == null) {
			CertificateException failure = candidate.trustManager().getDeferredFailure(candidate.socket());
			return reject(candidate, failure == null ? new IOException("Certificate is not trusted") : failure);
		}

		CompletableFuture<Boolean> decision;
		try {
			decision = Objects.requireNonNull(candidate.trustCallback().apply(certificate), "trust callback result");
		} catch (Exception e) {
			return reject(candidate, new IOException("Certificate trust callback failed", e));
		}

		PreConfigurationKeepalive keepalive;
		try {
			keepalive = new PreConfigurationKeepalive(candidate.socket(), candidate.hostHeader(), candidate.secret(), preConfigurationKeepaliveInterval,
					PRE_CONFIGURATION_KEEPALIVE_EXECUTOR, candidate.clientAlive());
		} catch (IOException e) {
			closeQuietly(candidate.socket());
			return CompletableFuture.failedFuture(e);
		}
		return decision.handle((trusted, error) -> {
			// The heartbeat must be gone before the connection's own requests start, so a straggler heartbeat record can never misframe the first response.
			keepalive.retire();
			if (error != null) {
				closeQuietly(candidate.socket());
				Throwable cause = Throwables.unwrap(error);
				if (cause instanceof CertificateTrustCancelledException cancelled) throw new CompletionException(cancelled);
				throw new CompletionException(new IOException("Certificate trust decision failed", cause));
			}
			if (!trusted) {
				candidate.socket().getSession().invalidate();
				closeQuietly(candidate.socket());
				throw new CompletionException(new IOException("User rejected certificate"));
			}
			try {
				candidate.sessionTrust().accept(certificate);
				return null;
			} catch (CertificateException e) {
				closeQuietly(candidate.socket());
				throw new CompletionException(e);
			}
		});
	}

	/** The peer certificates a resumed session carries; null when the JDK has none to show. */
	private static X509Certificate[] resumedChain(Candidate candidate) {
		try {
			Certificate[] peers = candidate.socket().getSession().getPeerCertificates();
			return peers instanceof X509Certificate[] certificates ? certificates : null;
		} catch (Throwable e) {
			return null;
		}
	}

	private static CompletableFuture<Void> reject(Candidate candidate, Throwable error) {
		// The condemned handshake's session dies with the verdict: a cached session must never outlive its
		// rejection, or a later lane could resume past the ladder.
		candidate.socket().getSession().invalidate();
		closeQuietly(candidate.socket());
		return CompletableFuture.failedFuture(error);
	}
}
