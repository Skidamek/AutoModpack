package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuingDistributionPoint;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import pl.skidam.automodpack_core.protocol.LeafRevocation.Status;
import pl.skidam.automodpack_core.utils.FetchCache;

/** The leaf CRL check against a local CA, its CRLs served over plain http, and the trust ladder's silent CA step. */
class LeafRevocationTest {

	private static final Duration DEADLINE = Duration.ofSeconds(4);
	private static final String HTTP = "/ca.crl";
	private static final String TLS = "/tls/ca.crl";
	private static final X500Name CA_NAME = new X500Name("CN=Revocation Test CA");

	private record Served(byte[] body, long delayMillis, WireCodec encoding) {}

	private final Map<String, Served> served = new ConcurrentHashMap<>();
	private HttpServer server;
	private KeyPair caKeys;
	private X509Certificate ca;
	private KeyPair leafKeys;
	private final AtomicLong serials = new AtomicLong(100);

	@BeforeEach
	void setUp() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/", exchange -> {
			Served answer = served.get(exchange.getRequestURI().getPath());
			try {
				if (answer != null && answer.delayMillis() > 0) Thread.sleep(answer.delayMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (answer == null || answer.body() == null) {
				exchange.sendResponseHeaders(404, -1);
			} else {
				if (answer.encoding() != null) exchange.getResponseHeaders().set("Content-Encoding", answer.encoding().wireName());
				exchange.sendResponseHeaders(200, answer.body().length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(answer.body());
				}
			}
			exchange.close();
		});
		server.start();
		caKeys = NetUtils.generateKeyPair();
		JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(CA_NAME, BigInteger.ONE, Date.from(Instant.now().minusSeconds(3600)), Date.from(Instant.now().plusSeconds(86_400)), CA_NAME,
				caKeys.getPublic());
		builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
		builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
		ca = new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate())));
		leafKeys = NetUtils.generateKeyPair();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void aRevokedLeafIsRevokedAndAnUnlistedOneIsGood() throws Exception {
		X509Certificate revoked = leaf(HTTP);
		X509Certificate good = leaf(HTTP);
		serve(HTTP, crl(caKeys, Instant.now().plusSeconds(3600), null, revoked.getSerialNumber()));
		assertEquals(Status.REVOKED, check(revoked, new FetchCache(null)));
		assertEquals(Status.GOOD, check(good, new FetchCache(null)));
	}

	@Test
	void aLeafWithoutAnHttpDistributionPointHasNothingToCheck() throws Exception {
		X509Certificate leaf = leaf(null);
		assertEquals(Status.GOOD, LeafRevocation.check(leaf, new X509Certificate[]{leaf, ca}, new FetchCache(null), DEADLINE).get(10, TimeUnit.SECONDS));
	}

	@Test
	void aCrlTheIssuerDidNotSignOrForAnotherShardDecidesNothing() throws Exception {
		X509Certificate leaf = leaf(HTTP);
		byte[] foreign = crl(NetUtils.generateKeyPair(), Instant.now().plusSeconds(3600), null, leaf.getSerialNumber());
		byte[] shard = crl(caKeys, Instant.now().plusSeconds(3600), url("/other-shard.crl"), leaf.getSerialNumber());
		serve(HTTP, foreign);
		serve(TLS, shard);
		assertEquals(Status.UNKNOWN, check(leaf, new FetchCache(null)));
	}

	@Test
	void aBlackHoledSourceLosesToTheOtherWellUnderTheDeadline() throws Exception {
		X509Certificate leaf = leaf(HTTP);
		byte[] revoking = crl(caKeys, Instant.now().plusSeconds(3600), null, leaf.getSerialNumber());
		served.put(HTTP, new Served(null, 10_000, null));
		serve(TLS, revoking);
		assertFast(Status.REVOKED, () -> check(leaf, new FetchCache(null)));
		served.put(TLS, new Served(null, 10_000, null));
		serve(HTTP, revoking);
		assertFast(Status.REVOKED, () -> check(leaf, new FetchCache(null)));
	}

	@Test
	void theRealHttpsVariantFailingLeavesHttpToDecide() throws Exception {
		// The production pair: the https twin of the distribution point meets this plain-http server and never answers.
		X509Certificate leaf = leaf(HTTP);
		serve(HTTP, crl(caKeys, Instant.now().plusSeconds(3600), null));
		assertFast(Status.GOOD, () -> LeafRevocation.check(leaf, new X509Certificate[]{leaf, ca}, new FetchCache(null), DEADLINE).get(10, TimeUnit.SECONDS));
	}

	@Test
	void aFastBadAnswerNeverBeatsASlowerValidOne() throws Exception {
		X509Certificate leaf = leaf(HTTP);
		byte[] revoking = crl(caKeys, Instant.now().plusSeconds(3600), null, leaf.getSerialNumber());
		List<Served> bad = List.of(new Served(crl(NetUtils.generateKeyPair(), Instant.now().plusSeconds(3600), null), 0, null),
				new Served("not a CRL".getBytes(StandardCharsets.UTF_8), 0, null), new Served(bomb(), 0, WireCodec.GZIP));
		for (Served fast : bad) {
			for (String[] sides : new String[][]{{HTTP, TLS}, {TLS, HTTP}}) {
				served.put(sides[0], fast);
				served.put(sides[1], new Served(revoking, 300, null));
				assertEquals(Status.REVOKED, check(leaf, new FetchCache(null)), "a bad answer on " + sides[0] + " lost to the valid one");
			}
		}
	}

	@Test
	void whenBothSourcesFailAStaleCopyStandsUntilItsNextUpdate(@TempDir Path directory) throws Exception {
		X509Certificate leaf = leaf(HTTP);
		FetchCache cache = new FetchCache(directory);
		serve(HTTP, crl(caKeys, Instant.now().plusSeconds(86_400), null, leaf.getSerialNumber()));
		assertEquals(Status.REVOKED, check(leaf, cache));
		age(directory);
		served.clear();
		assertEquals(Status.REVOKED, check(leaf, cache), "both sources answer 404");
		age(directory);
		served.put(HTTP, new Served(null, 3_000, null));
		served.put(TLS, new Served(null, 3_000, null));
		assertEquals(Status.REVOKED, check(leaf, cache, Duration.ofMillis(500)), "both sources hang past the deadline");
		assertEquals(Status.UNKNOWN, check(leaf, new FetchCache(null), Duration.ofMillis(500)), "no copy and no answer");

		byte[] expired = crl(caKeys, Instant.now().minusSeconds(3600), null, leaf.getSerialNumber());
		serve(HTTP, expired);
		serve(TLS, expired);
		assertEquals(Status.UNKNOWN, check(leaf, new FetchCache(null)), "a CRL past its nextUpdate decides nothing");
	}

	@Test
	void anOversizedOrSlowCrlIsUnknown() throws Exception {
		X509Certificate leaf = leaf(HTTP);
		served.put(HTTP, new Served(bomb(), 0, WireCodec.GZIP));
		served.put(TLS, new Served(bomb(), 0, WireCodec.GZIP));
		assertEquals(Status.UNKNOWN, check(leaf, new FetchCache(null)));

		served.put(HTTP, new Served(crl(caKeys, Instant.now().plusSeconds(3600), null), 3_000, null));
		served.put(TLS, new Served(crl(caKeys, Instant.now().plusSeconds(3600), null), 3_000, null));
		assertFast(Status.UNKNOWN, () -> check(leaf, new FetchCache(null), Duration.ofMillis(300)));
	}

	@Test
	void anIssuerMissingFromThePresentedChainIsUnknown() throws Exception {
		X509Certificate leaf = leaf(HTTP);
		assertEquals(Status.UNKNOWN, LeafRevocation.check(leaf, new X509Certificate[]{leaf}, new FetchCache(null), DEADLINE).get(10, TimeUnit.SECONDS));
	}

	@Test
	void theSilentCaStepRejectsARevokedLeafWithoutAskingThePlayer() throws Exception {
		X509Certificate leaf = leaf(HTTP);
		serve(HTTP, crl(caKeys, Instant.now().plusSeconds(3600), null, leaf.getSerialNumber()));
		AtomicBoolean asked = new AtomicBoolean();
		ExecutionException failure = assertThrows(ExecutionException.class, () -> ladder(leaf, asked));
		assertTrue(failure.getCause().getMessage().contains("revoked by its CA"), failure.getCause()::toString);
		assertFalse(asked.get());
	}

	@Test
	void theSilentCaStepTrustsAGoodLeafAndAnUnknownOneFallsThroughToThePlayer() throws Exception {
		X509Certificate leaf = leaf(HTTP);
		serve(HTTP, crl(caKeys, Instant.now().plusSeconds(3600), null));
		AtomicBoolean asked = new AtomicBoolean();
		ladder(leaf, asked);
		assertFalse(asked.get(), "a good leaf trusts silently");

		served.clear();
		ladder(leaf, asked);
		assertTrue(asked.get(), "an unreachable CRL never trusts silently; the record step finds no policy for an IP origin and the player decides");
	}

	/** The race over this server's two stand-ins for the http and https sources, cached under the http one. */
	private Status check(X509Certificate leaf, FetchCache cache) throws Exception {
		return check(leaf, cache, DEADLINE);
	}

	private Status check(X509Certificate leaf, FetchCache cache, Duration deadline) throws Exception {
		return LeafRevocation.check(leaf, new X509Certificate[]{leaf, ca}, List.of(url(HTTP), url(TLS)), cache, deadline).get(10, TimeUnit.SECONDS);
	}

	private interface Check {
		Status run() throws Exception;
	}

	private static void assertFast(Status expected, Check check) throws Exception {
		long start = System.nanoTime();
		assertEquals(expected, check.run());
		long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		assertTrue(millis < 1_500, "took " + millis + " ms");
	}

	private static byte[] bomb() throws IOException {
		ByteArrayOutputStream bomb = new ByteArrayOutputStream();
		try (OutputStream out = WireCodec.GZIP.wrap(bomb)) {
			out.write(new byte[8 * 1024 * 1024 + 1]); // one past the pool's decoded-body cap
		}
		return bomb.toByteArray();
	}

	private static void age(Path directory) throws IOException {
		try (var files = Files.list(directory)) {
			for (Path file : files.toList()) Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(FetchCache.MAX_AGE).minusSeconds(60)));
		}
	}

	/** One handshake to a local TLS server presenting {@code leaf} and the CA, judged by the ladder with the player accepting if asked. */
	private void ladder(X509Certificate leaf, AtomicBoolean asked) throws Exception {
		char[] password = "test".toCharArray();
		KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
		keys.load(null, null);
		keys.setKeyEntry("leaf", leafKeys.getPrivate(), password, new Certificate[]{leaf, ca});
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keys, password);
		SSLContext serverContext = SSLContext.getInstance("TLSv1.3");
		serverContext.init(keyManagers.getKeyManagers(), null, null);
		KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
		anchors.load(null, null);
		anchors.setCertificateEntry("ca", ca);

		try (SSLServerSocket listener = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			CompletableFuture.runAsync(() -> {
				try (SSLSocket accepted = (SSLSocket) listener.accept()) {
					accepted.startHandshake();
					accepted.getInputStream().read();
				} catch (IOException ignored) {
				}
			});
			var trust = new CustomizableTrustManager.SessionTrust("127.0.0.1:25565", null);
			var manager = new CustomizableTrustManager(trust, null, anchors);
			SSLSocket socket = CandidateTrustValidation.wrapWithTls(new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()), CandidateTrustValidation.newSslContext(manager),
					"127.0.0.1", listener.getLocalPort());
			try (socket) {
				CandidateTrustValidation.validate(new CandidateTrustValidation.Candidate(socket, manager, trust, "127.0.0.1", "127.0.0.1", certificate -> {
					asked.set(true);
					return CompletableFuture.completedFuture(true);
				})).get(10, TimeUnit.SECONDS);
			}
		}
	}

	private X509Certificate leaf(String crlPath) throws Exception {
		X500Name subject = new X500Name("CN=127.0.0.1");
		JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(CA_NAME, BigInteger.valueOf(serials.incrementAndGet()), Date.from(Instant.now().minusSeconds(60)),
				Date.from(Instant.now().plusSeconds(3600)), subject, leafKeys.getPublic());
		builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
		if (crlPath != null) {
			GeneralNames location = new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, url(crlPath)));
			builder.addExtension(Extension.cRLDistributionPoints, false, new CRLDistPoint(new DistributionPoint[]{new DistributionPoint(new DistributionPointName(location), null, null)}));
		}
		return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(caKeys.getPrivate())));
	}

	private byte[] crl(KeyPair signer, Instant nextUpdate, String shard, BigInteger... revoked) throws Exception {
		X509v2CRLBuilder builder = new X509v2CRLBuilder(CA_NAME, Date.from(Instant.now().minusSeconds(7200)));
		builder.setNextUpdate(Date.from(nextUpdate));
		for (BigInteger serial : revoked) builder.addCRLEntry(serial, Date.from(Instant.now().minusSeconds(600)), CRLReason.keyCompromise);
		if (shard != null) {
			DistributionPointName scope = new DistributionPointName(new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, shard)));
			builder.addExtension(Extension.issuingDistributionPoint, true, new IssuingDistributionPoint(scope, false, false));
		}
		X509CRL crl = new JcaX509CRLConverter().getCRL(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(signer.getPrivate())));
		return crl.getEncoded();
	}

	private void serve(String path, byte[] body) {
		served.put(path, new Served(body, 0, null));
	}

	private String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}
}
