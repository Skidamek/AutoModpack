package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.getFingerprint;

import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
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
 * socket: a deferred self-signed certificate is accepted on a DNSSEC fingerprint match or the player's explicit
 * decision, heartbeated while the human decides. On acceptance the session trust pins the certificate, so every later
 * handshake on the same SSLContext passes without asking again.
 */
public final class CandidateTrustValidation {

	/** One daemon thread heartbeats every candidate parked on a certificate-trust decision. */
	private static final ScheduledExecutorService PRE_CONFIGURATION_KEEPALIVE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(
			new CustomThreadFactoryBuilder().setNameFormat("AutoModpack PreConfigurationKeepalive #%d").setDaemon(true).build());

	/** One transport candidate: its probe socket, the trust manager that handed it over, and who may accept the certificate. */
	public record Candidate(SSLSocket socket, CustomizableTrustManager trustManager, CustomizableTrustManager.SessionTrust sessionTrust, String originHost, String endpointHost,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback, BooleanSupplier clientAlive) {}

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
	public static SSLSocket wrapWithTls(Socket plainSocket, SSLContext context, String originHost, int endpointPort) throws IOException {
		SSLSocketFactory factory = context.getSocketFactory();
		SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, originHost, endpointPort, true);
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
		X509Certificate certificate = candidate.trustManager().getDeferredCertificate();
		if (certificate == null) return CompletableFuture.completedFuture(null);

		try {
			certificate.checkValidity();
		} catch (CertificateException e) {
			return reject(candidate, new IOException("Untrusted certificate is not valid", e));
		}

		CompletableFuture<Void> validation = DnsPinResolver.resolvePinAsync(candidate.originHost()).thenCompose(result -> {
			if (result instanceof DnsPinResolver.Authoritative authoritative) {
				try {
					String fingerprint = getFingerprint(certificate);
					if (!authoritative.fingerprint().equals(fingerprint)) {
						return reject(candidate, new IOException("Certificate does not match the DNSSEC fingerprint for " + candidate.originHost()));
					}
					candidate.sessionTrust().accept(certificate);
					LOGGER.info("Trusting the self-signed certificate from {} because it matches the DNSSEC fingerprint for {}", candidate.endpointHost(), candidate.originHost());
					return CompletableFuture.completedFuture(null);
				} catch (CertificateException e) {
					return reject(candidate, new IOException("Failed to validate DNSSEC-pinned certificate", e));
				}
			}
			if (result instanceof DnsPinResolver.Misconfigured misconfigured) {
				return reject(candidate, new IOException("Invalid DNSSEC AutoModpack fingerprint for " + candidate.originHost() + ": " + misconfigured.reason()));
			}
			return requestManualTrust(candidate, certificate, preConfigurationKeepaliveInterval);
		});
		return validation.whenComplete((ignored, error) -> {
			if (error != null) closeQuietly(candidate.socket());
		});
	}

	private static CompletableFuture<Void> requestManualTrust(Candidate candidate, X509Certificate certificate, Duration preConfigurationKeepaliveInterval) {
		if (candidate.trustCallback() == null) {
			CertificateException failure = candidate.trustManager().getDeferredFailure();
			return reject(candidate, failure == null ? new IOException("Certificate is not trusted") : failure);
		}

		CompletableFuture<Boolean> decision;
		try {
			decision = Objects.requireNonNull(candidate.trustCallback().apply(certificate), "trust callback result");
		} catch (Exception e) {
			return reject(candidate, new IOException("Certificate trust callback failed", e));
		}

		PreConfigurationKeepalive keepalive = new PreConfigurationKeepalive(candidate.socket(), preConfigurationKeepaliveInterval, PRE_CONFIGURATION_KEEPALIVE_EXECUTOR,
				candidate.clientAlive());
		return decision.handle((trusted, error) -> {
			// The heartbeat must be gone before the negotiation writes start, so a straggler keepalive record can
			// never land after the configuration echo and misframe the configured connection.
			keepalive.retire();
			if (error != null) {
				closeQuietly(candidate.socket());
				Throwable cause = Throwables.unwrap(error);
				if (cause instanceof CertificateTrustCancelledException cancelled) throw new CompletionException(cancelled);
				throw new CompletionException(new IOException("Certificate trust decision failed", cause));
			}
			if (!trusted) {
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

	private static CompletableFuture<Void> reject(Candidate candidate, Throwable error) {
		closeQuietly(candidate.socket());
		return CompletableFuture.failedFuture(error);
	}

	static void closeQuietly(AutoCloseable closeable) {
		try {
			closeable.close();
		} catch (Exception ignored) {
		}
	}
}
