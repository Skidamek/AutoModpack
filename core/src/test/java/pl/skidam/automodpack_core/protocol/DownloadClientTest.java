package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.Jsons;

class DownloadClientTest {

	@Test
	void acceptsValidSelfSignedCertificateForDnsFallback() throws Exception {
		X509Certificate certificate = NetUtils.selfSign(NetUtils.generateKeyPair());

		assertTrue(DownloadClient.isSelfSigned(certificate));
	}

	@Test
	void rejectsCaIssuedCertificateForDnsFallback() throws Exception {
		KeyPair issuerKeyPair = NetUtils.generateKeyPair();
		X509Certificate issuer = NetUtils.selfSign(issuerKeyPair);
		X509Certificate leaf = issueCertificate(new X500Name(issuer.getSubjectX500Principal().getName()), new X500Name("CN=AutoModpack CA-issued Certificate"),
				null, NetUtils.generateKeyPair(), issuerKeyPair, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));

		assertFalse(DownloadClient.isSelfSigned(leaf));
	}

	@Test
	void recognizesExpiredCertificateAsSelfSigned() throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X500Name subject = new X500Name("CN=Expired AutoModpack Certificate");
		X509Certificate certificate = issueCertificate(subject, subject, null, keyPair, keyPair, Instant.now().minusSeconds(7200),
				Instant.now().minusSeconds(3600));

		assertTrue(DownloadClient.isSelfSigned(certificate));
	}

	@Test
	void authenticatesOriginWhileConnectingToDifferentRoute() throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X500Name subject = new X500Name("CN=origin.example");
		X509Certificate certificate = issueCertificate(subject, subject, "origin.example", keyPair, keyPair, Instant.now().minusSeconds(60),
				Instant.now().plusSeconds(3600));

		withTlsServer(keyPair, certificate, port -> {
			var connectionInfo = new Jsons.ConnectionInfo(InetSocketAddress.createUnresolved("origin.example", 25565),
					InetSocketAddress.createUnresolved("route.example", port), false, null, null);
			var route = new InetSocketAddress("127.0.0.1", port);

			assertDoesNotThrow(() -> {
				var connection = new PreValidationConnection(route, connectionInfo, clientContext(certificate));
				connection.getSocket().close();
			});
		});
	}

	@Test
	void exactPinConstrainsOtherwiseTrustedCertificate() throws Exception {
		X509Certificate certificate = NetUtils.selfSign(NetUtils.generateKeyPair());
		KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
		trustStore.load(null);

		var matching = new CustomizableTrustManager(trustStore, null, "origin.example", NetUtils.getFingerprint(certificate));
		assertDoesNotThrow(() -> matching.checkServerTrusted(new X509Certificate[]{certificate}, "RSA"));

		var mismatching = new CustomizableTrustManager(trustStore, null, "origin.example", "0".repeat(64));
		assertThrows(CertificatePinMismatchException.class, () -> mismatching.checkServerTrusted(new X509Certificate[]{certificate}, "RSA"));
		assertThrows(CertificatePinMismatchException.class, () -> mismatching.checkServerTrusted(new X509Certificate[]{certificate}, "RSA", (SSLEngine) null));
	}

	@Test
	void rejectsCertificateValidOnlyForAdvertisedRoute() throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X500Name subject = new X500Name("CN=route.example");
		X509Certificate certificate = issueCertificate(subject, subject, "route.example", keyPair, keyPair, Instant.now().minusSeconds(60),
				Instant.now().plusSeconds(3600));

		withTlsServer(keyPair, certificate, port -> {
			var connectionInfo = new Jsons.ConnectionInfo(InetSocketAddress.createUnresolved("origin.example", 25565),
					InetSocketAddress.createUnresolved("route.example", port), false, null, null);
			var route = new InetSocketAddress("127.0.0.1", port);

			assertThrows(IOException.class, () -> new PreValidationConnection(route, connectionInfo, clientContext(certificate)));
		});
	}

	private static void withTlsServer(KeyPair keyPair, X509Certificate certificate, ThrowingPortConsumer test) throws Exception {
		SSLServerSocket server = (SSLServerSocket) serverContext(keyPair, certificate).getServerSocketFactory().createServerSocket(0, 1,
				InetAddress.getLoopbackAddress());
		server.setEnabledProtocols(new String[]{"TLSv1.3"});

		CompletableFuture<Void> handshake = CompletableFuture.runAsync(() -> {
			try (SSLSocket socket = (SSLSocket) server.accept()) {
				socket.setEnabledProtocols(new String[]{"TLSv1.3"});
				socket.startHandshake();
			} catch (IOException ignored) {
			}
		});

		try (server) {
			test.accept(server.getLocalPort());
		} finally {
			handshake.get(5, TimeUnit.SECONDS);
		}
	}

	private static SSLContext serverContext(KeyPair keyPair, X509Certificate certificate) throws Exception {
		char[] password = "test-password".toCharArray();
		KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
		keyStore.load(null);
		keyStore.setKeyEntry("server", keyPair.getPrivate(), password, new Certificate[]{certificate});

		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keyStore, password);

		SSLContext context = SSLContext.getInstance("TLSv1.3");
		context.init(keyManagers.getKeyManagers(), null, new SecureRandom());
		return context;
	}

	private static SSLContext clientContext(X509Certificate certificate) throws Exception {
		KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
		trustStore.load(null);
		trustStore.setCertificateEntry("server", certificate);

		TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trustManagers.init(trustStore);

		SSLContext context = SSLContext.getInstance("TLSv1.3");
		context.init(null, trustManagers.getTrustManagers(), new SecureRandom());
		return context;
	}

	private static X509Certificate issueCertificate(X500Name issuer, X500Name subject, String dnsName, KeyPair subjectKeyPair, KeyPair signingKeyPair,
			Instant notBefore, Instant notAfter) throws Exception {
		var builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.ONE, Date.from(notBefore), Date.from(notAfter), subject, subjectKeyPair.getPublic());
		if (dnsName != null) builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, dnsName)));
		var signer = new JcaContentSignerBuilder("SHA256WithRSA").build(signingKeyPair.getPrivate());
		return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
	}

	@FunctionalInterface
	private interface ThrowingPortConsumer {
		void accept(int port) throws Exception;
	}
}
