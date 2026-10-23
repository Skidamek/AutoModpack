package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import pl.skidam.automodpack_core.utils.FetchCache;

class LeafRevocationTest {

	private static final X500Name CA_NAME = new X500Name("CN=Test CA");

	@Test
	void aLeafWithNoHttpCrlDistributionPointIsGood() throws Exception {
		KeyPair ca = NetUtils.generateKeyPair();
		KeyPair leafKeys = NetUtils.generateKeyPair();
		X509Certificate issuer = caCert(ca);
		X509Certificate leaf = leafCert(ca, leafKeys, null);
		assertEquals(LeafRevocation.Status.GOOD, LeafRevocation.check(leaf, new X509Certificate[]{leaf, issuer}, new FetchCache(null), Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS));
	}

	@Test
	void aRevokedLeafIsRevoked(@TempDir Path directory) throws Exception {
		KeyPair ca = NetUtils.generateKeyPair();
		KeyPair leafKeys = NetUtils.generateKeyPair();
		X509Certificate issuer = caCert(ca);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		String crlUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/crl";
		X509Certificate leaf = leafCert(ca, leafKeys, crlUrl);
		byte[] crl = crl(ca, leaf.getSerialNumber(), 1);
		server.createContext("/crl", exchange -> {
			exchange.sendResponseHeaders(200, crl.length);
			try (OutputStream out = exchange.getResponseBody()) { out.write(crl); }
		});
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
		try {
			FetchCache cache = new FetchCache(directory);
			assertEquals(LeafRevocation.Status.REVOKED, LeafRevocation.check(leaf, new X509Certificate[]{leaf, issuer}, List.of(crlUrl), cache, Duration.ofSeconds(4)).get(8, TimeUnit.SECONDS));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void anOlderCrlThanTheCachedCopyIsRefused(@TempDir Path directory) throws Exception {
		KeyPair ca = NetUtils.generateKeyPair();
		KeyPair leafKeys = NetUtils.generateKeyPair();
		X509Certificate issuer = caCert(ca);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		String crlUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/crl";
		X509Certificate leaf = leafCert(ca, leafKeys, crlUrl);
		byte[] fresh = crl(ca, null, 2);
		byte[] older = crl(ca, leaf.getSerialNumber(), 1);
		// Seed the cache with the fresh (non-revoking) CRL
		FetchCache cache = new FetchCache(directory);
		server.createContext("/crl", exchange -> {
			exchange.sendResponseHeaders(200, fresh.length);
			try (OutputStream out = exchange.getResponseBody()) { out.write(fresh); }
		});
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
		try {
			assertEquals(LeafRevocation.Status.GOOD, LeafRevocation.check(leaf, new X509Certificate[]{leaf, issuer}, List.of(crlUrl), cache, Duration.ofSeconds(4)).get(8, TimeUnit.SECONDS));
			server.removeContext("/crl");
			server.createContext("/crl", exchange -> {
				exchange.sendResponseHeaders(200, older.length);
				try (OutputStream out = exchange.getResponseBody()) { out.write(older); }
			});
			// Force refetch: the cached file is fresh by mtime, so write an older mtime... easier: new cache dir with pre-seeded older-looking wait
			// Instead: put older body as stored and make MAX_AGE miss by using a cache that will refetch.
			// Simpler assertion: settled refuses olderThan - exercise via check with stored older and serving older.
			java.nio.file.Files.write(directory.resolve(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(crlUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8)))), fresh);
			// Age the file so get() refetches
			directory.resolve(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(crlUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).toFile().setLastModified(System.currentTimeMillis() - FetchCache.MAX_AGE.toMillis() - 60_000);
			assertEquals(LeafRevocation.Status.GOOD, LeafRevocation.check(leaf, new X509Certificate[]{leaf, issuer}, List.of(crlUrl), cache, Duration.ofSeconds(4)).get(8, TimeUnit.SECONDS),
					"a replayed older CRL that revokes must not beat the cached newer copy");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void aLeafNamingNoHttpCrlKeepsSilentTrustDocumented() throws Exception {
		// Mirrors v8 commit 05: no http CDP => GOOD (silent trust).
		assertNull(LeafRevocation.httpCrlUrl(NetUtils.selfSign(NetUtils.generateKeyPair())));
	}

	private static X509Certificate caCert(KeyPair ca) throws Exception {
		return new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(CA_NAME, BigInteger.ONE, Date.from(Instant.now().minusSeconds(3600)), Date.from(Instant.now().plusSeconds(86_400)), CA_NAME, ca.getPublic())
				.addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
				.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
				.build(new JcaContentSignerBuilder("SHA256withRSA").build(ca.getPrivate())));
	}

	private static X509Certificate leafCert(KeyPair ca, KeyPair leafKeys, String crlUrl) throws Exception {
		X500Name subject = new X500Name("CN=leaf.example");
		var builder = new JcaX509v3CertificateBuilder(CA_NAME, BigInteger.TEN, Date.from(Instant.now().minusSeconds(3600)), Date.from(Instant.now().plusSeconds(86_400)), subject, leafKeys.getPublic());
		if (crlUrl != null) {
			builder.addExtension(Extension.cRLDistributionPoints, false, new CRLDistPoint(new DistributionPoint[]{new DistributionPoint(new DistributionPointName(new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl))), null, null)}));
		}
		return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(ca.getPrivate())));
	}

	private static byte[] crl(KeyPair ca, BigInteger revokedSerial, int number) throws Exception {
		X509v2CRLBuilder builder = new X509v2CRLBuilder(CA_NAME, Date.from(Instant.now().minusSeconds(60).minusSeconds(number == 1 ? 3600 : 0)));
		builder.setNextUpdate(Date.from(Instant.now().plusSeconds(86_400)));
		builder.addExtension(Extension.cRLNumber, false, new CRLNumber(BigInteger.valueOf(number)));
		if (revokedSerial != null) builder.addCRLEntry(revokedSerial, Date.from(Instant.now().minusSeconds(600)), CRLReason.keyCompromise);
		return builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(ca.getPrivate())).getEncoded();
	}
}
