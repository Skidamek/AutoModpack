package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConnectionJsons;

class DownloadClientTest {
	/**
	 * Upper bound for async waits on loopback handshakes that complete in milliseconds when warm.
	 * Cold CI runners (Windows especially) have blown past five seconds here; a generous bound only
	 * ever costs time on a genuine hang.
	 */
	private static final int AWAIT_SECONDS = 20;

	/** The trickle fuse budget: a 4 MiB slice drains inside the rate floor (exactly 1024 s), a tiny slice never sits under the 90 s stall window. */
	@Test
	void takeBudgetSizesBySliceWithAStallFloor() {
		assertEquals(90_000_000_000L, DownloadClient.ObjectTransfer.takeBudgetNanos(1));
		assertEquals(1_024_000_000_000L, DownloadClient.ObjectTransfer.takeBudgetNanos(4L * 1024 * 1024));
	}

	/** Throttled retries wait out the provider's window (clamped by the wire) or a bounded jittered default; plain failures retry immediately. */
	@Test
	void throttledRetryDelayHonorsRetryAfterAndJittersTheFallback() {
		assertEquals(1000, DownloadClient.ObjectTransfer.retryDelayMillis(new HostThrottleException(503, 1000)));
		long fallback = DownloadClient.ObjectTransfer.retryDelayMillis(new HostThrottleException(429, -1));
		assertTrue(fallback >= 1000 && fallback < 2000, "the jittered default must stay in its bounded band: " + fallback);
		assertEquals(0, DownloadClient.ObjectTransfer.retryDelayMillis(new IOException("a lane death retries at once")));
	}

	@Test
	void localDestinationOpenFailureHasTypedStorageBoundary(@TempDir Path directory) throws Exception {
		Path destination = Files.createDirectory(directory.resolve("destination"));

		assertThrows(LocalStorageException.class, () -> LocalFileWriter.open(destination));
	}

	@Test
	void recognizesOnlyGenuinelySelfSignedCertificates() throws Exception {
		X509Certificate selfSigned = NetUtils.selfSign(NetUtils.generateKeyPair());
		KeyPair issuerKeyPair = NetUtils.generateKeyPair();
		X509Certificate issuer = NetUtils.selfSign(issuerKeyPair);
		X509Certificate issued = issueCertificate(new X500Name(issuer.getSubjectX500Principal().getName()), new X500Name("CN=Issued"), null,
				NetUtils.generateKeyPair(), issuerKeyPair, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));

		assertTrue(CustomizableTrustManager.isSelfSigned(selfSigned));
		assertFalse(CustomizableTrustManager.isSelfSigned(issued));
	}

	@Test
	void recognizesExpiredCertificateAsSelfSigned() throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X500Name subject = new X500Name("CN=Expired AutoModpack Certificate");
		X509Certificate certificate = issueCertificate(subject, subject, null, keyPair, keyPair, Instant.now().minusSeconds(7200),
				Instant.now().minusSeconds(3600));

		assertTrue(CustomizableTrustManager.isSelfSigned(certificate));
	}

	@Test
	void pinsPassDeferAndNothingRecovers() throws Exception {
		X509Certificate accepted = NetUtils.selfSign(NetUtils.generateKeyPair());
		X509Certificate changed = NetUtils.selfSign(NetUtils.generateKeyPair());
		String fingerprint = NetUtils.getFingerprint(accepted);

		// The exact pin passes the leaf straight through; a changed leaf defers quietly - the handshake never
		// hard-fails, and the ladder fails it outright: a pin is a pin, and no record, CA, or prompt recovers it.
		// The bare checkServerTrusted(chain, authType) variant keys its deferral on null.
		var configuredTrust = new CustomizableTrustManager.SessionTrust("origin.example:25565", fingerprint);
		var configuredManager = new CustomizableTrustManager(configuredTrust, null);
		assertDoesNotThrow(() -> configuredManager.checkServerTrusted(new X509Certificate[]{accepted}, "RSA"));
		assertNull(configuredManager.getDeferredFailure(null));
		assertTrue(configuredManager.isPinMatched(null), "the pin match is marked, so the ladder accepts it without judging");
		assertDoesNotThrow(() -> configuredManager.checkServerTrusted(new X509Certificate[]{changed}, "RSA"));
		assertSame(changed, configuredManager.getDeferredCertificate(null));
		CertificatePinMismatchException mismatch = configuredTrust.mismatch(changed);
		assertEquals(fingerprint, mismatch.getExpectedFingerprint());
		assertEquals(NetUtils.getFingerprint(changed), mismatch.getPresentedFingerprint());

		// A session-accepted pin constrains later handshakes the same way, and a conflicting accept is refused.
		var sessionTrust = new CustomizableTrustManager.SessionTrust("origin.example:25565", null);
		sessionTrust.accept(accepted);
		var sessionManager = new CustomizableTrustManager(sessionTrust, null);
		assertDoesNotThrow(() -> sessionManager.checkServerTrusted(new X509Certificate[]{accepted}, "RSA"));
		assertDoesNotThrow(() -> sessionManager.checkServerTrusted(new X509Certificate[]{changed}, "RSA"));
		assertSame(changed, sessionManager.getDeferredCertificate(null));
		assertThrows(CertificatePinMismatchException.class, () -> sessionTrust.accept(changed));

		// Nothing recovers the changed leaf at the trust-manager level: it stays deferred for the ladder, which
		// fails a pinned origin before any record lookup. The pin leaves only when the player revokes it.
		assertSame(changed, configuredManager.getDeferredCertificate(null));
	}

	@Test
	void theOriginCaStepAsksTheTypedNameOfTheLeaf() throws Exception {
		KeyPair caKeys = NetUtils.generateKeyPair();
		X500Principal caName = new X500Principal("CN=Origin CA");
		X509Certificate ca = mint(caName, caKeys.getPublic(), caName, caKeys.getPrivate(), true, List.of());

		KeyPair leafKeys = NetUtils.generateKeyPair();
		X500Principal leafName = new X500Principal("CN=pack.example.com");
		X509Certificate covering = mint(leafName, leafKeys.getPublic(), caName, caKeys.getPrivate(), false,
				List.<Object[]>of(new Object[]{GeneralName.dNSName, "pack.example.com"}));
		X509Certificate wildcard = mint(leafName, leafKeys.getPublic(), caName, caKeys.getPrivate(), false,
				List.<Object[]>of(new Object[]{GeneralName.dNSName, "*.example.com"}));
		X509Certificate elsewhere = mint(leafName, leafKeys.getPublic(), caName, caKeys.getPrivate(), false,
				List.<Object[]>of(new Object[]{GeneralName.dNSName, "other.example.com"}));
		X509Certificate addressed = mint(leafName, leafKeys.getPublic(), caName, caKeys.getPrivate(), false,
				List.<Object[]>of(new Object[]{GeneralName.iPAddress, "127.0.0.1"}));

		assertTrue(CandidateTrustValidation.leafCoversOrigin(covering, "pack.example.com"), "the exact origin name covers");
		assertTrue(CandidateTrustValidation.leafCoversOrigin(wildcard, "a.example.com"), "a leftmost wildcard covers one label");
		assertFalse(CandidateTrustValidation.leafCoversOrigin(wildcard, "a.b.example.com"), "a wildcard never covers two labels");
		assertFalse(CandidateTrustValidation.leafCoversOrigin(wildcard, "example.com"), "a wildcard never covers the bare domain");
		assertFalse(CandidateTrustValidation.leafCoversOrigin(elsewhere, "pack.example.com"), "another name does not cover");
		assertTrue(CandidateTrustValidation.leafCoversOrigin(addressed, "127.0.0.1"), "an IP origin matches its iPAddress entry");
		assertFalse(CandidateTrustValidation.leafCoversOrigin(addressed, "127.0.0.2"), "another address does not cover");
	}

	@Test
	void aCaChainDefersCleanlyAndASelfSignedOneCarriesItsFailure() throws Exception {
		KeyPair caKeys = NetUtils.generateKeyPair();
		X500Principal caName = new X500Principal("CN=Origin CA");
		X509Certificate ca = mint(caName, caKeys.getPublic(), caName, caKeys.getPrivate(), true, List.of());
		KeyPair leafKeys = NetUtils.generateKeyPair();
		X509Certificate leaf = mint(new X500Principal("CN=pack.example.com"), leafKeys.getPublic(), caName, caKeys.getPrivate(), false,
				List.<Object[]>of(new Object[]{GeneralName.dNSName, "pack.example.com"}));

		KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
		store.load(null, null);
		store.setCertificateEntry("origin-ca", ca);
		var trust = new CustomizableTrustManager.SessionTrust("origin.example:25565", null);
		var manager = new CustomizableTrustManager(trust, null, store);

		// The CA-signed chain defers with no failure: exactly the gate the origin-anchored CA step requires.
		manager.checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA");
		assertNull(manager.getDeferredFailure(null));
		assertSame(leaf, manager.getDeferredCertificate(null));

		// A self-signed leaf defers with its failure, so it never reaches the CA step of the ladder.
		X509Certificate selfSigned = NetUtils.selfSign(NetUtils.generateKeyPair());
		manager.checkServerTrusted(new X509Certificate[]{selfSigned}, "RSA");
		assertNotNull(manager.getDeferredFailure(null));
	}

	/** A minimal WebPKI stand-in: a self-styled CA, or a leaf it signs, with dNSName and iPAddress SAN entries. */
	private static X509Certificate mint(X500Principal subject, PublicKey publicKey, X500Principal issuer, PrivateKey issuerKey, boolean certificateAuthority,
			List<Object[]> sans) throws Exception {
		Date from = new Date();
		JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, new BigInteger(159, new SecureRandom()),
				from, new Date(from.getTime() + 3600_000), subject, publicKey);
		if (certificateAuthority) builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
		if (!sans.isEmpty()) {
			GeneralName[] names = sans.stream().map(entry -> new GeneralName((Integer) entry[0], (String) entry[1])).toArray(GeneralName[]::new);
			builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
		}
		ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey);
		try (InputStream input = new ByteArrayInputStream(builder.build(signer).getEncoded())) {
			return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
		}
	}

	@Test
	void deferredTrustSendsNoApplicationBytesAndReusesSocket(@TempDir Path directory) throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		CompletableFuture<Boolean> decision = new CompletableFuture<>();

		try (TransferServer server = new TransferServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, null, null);
			CompletableFuture<DownloadClient> clientFuture = DownloadClient.createAsync(connectionInfo, null, ignored -> decision);

			assertEquals(-1, server.earlyApplicationByte().get(AWAIT_SECONDS, TimeUnit.SECONDS));
			assertFalse(clientFuture.isDone());
			decision.complete(true);

			try (DownloadClient client = clientFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
				// The HTTP connection carries no handshake of its own: the first application bytes are the first request.
				client.downloadSmallObject("hash".getBytes(StandardCharsets.UTF_8), directory.resolve("first"), -1L, null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertEquals(List.of("/objects/hash"), server.requests());
				assertEquals(1, server.acceptedConnections());
			}
		}
	}

	@Test
	void rejectedDeferredTrustClosesWithoutReconnect() throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		CompletableFuture<Boolean> decision = new CompletableFuture<>();

		try (TransferServer server = new TransferServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, null, null);
			CompletableFuture<DownloadClient> clientFuture = DownloadClient.createAsync(connectionInfo, null, ignored -> decision);

			assertEquals(-1, server.earlyApplicationByte().get(AWAIT_SECONDS, TimeUnit.SECONDS));
			decision.complete(false);
			assertThrows(Exception.class, () -> clientFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS));
			assertEquals(1, server.acceptedConnections());
		}
	}

	@Test
	void trustWaitKeepsTransportWarmAndStopsAfterConfiguration(@TempDir Path directory) throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		CompletableFuture<Boolean> decision = new CompletableFuture<>();

		try (TransferServer server = new TransferServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, null, null);
			CompletableFuture<DownloadClient> clientFuture = DownloadClient.createAsync(connectionInfo, null, ignored -> decision, Duration.ofMillis(100));

			long deadline = System.currentTimeMillis() + 5000;
			while (server.heartbeats() < 2 && System.currentTimeMillis() < deadline)
				Thread.sleep(20);
			assertTrue(server.heartbeats() >= 2, "the client parked on the trust decision must heartbeat periodically");
			assertFalse(clientFuture.isDone());

			decision.complete(true);
			try (DownloadClient client = clientFuture.get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
				client.downloadSmallObject("hash".getBytes(StandardCharsets.UTF_8), directory.resolve("first"), -1L, null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				int heartbeatsAtConfiguration = server.heartbeats();
				// The heartbeat retires with the trust decision: three parked-phase intervals later the connection has heard no straggler heartbeat.
				deadline = System.currentTimeMillis() + 500;
				while (System.currentTimeMillis() < deadline)
					Thread.sleep(20);
				assertEquals(heartbeatsAtConfiguration, server.heartbeats());
				assertEquals(List.of("/objects/hash"), server.requests());
			}
			assertEquals(1, server.acceptedConnections());
		}
	}

	@Test
	void abortTransfersFailsTheInFlightDownloadAndAnAbortedClientSendsNoMoreRequests(@TempDir Path directory) throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		String fingerprint = NetUtils.getFingerprint(certificate);
		try (LeasingServer server = new LeasingServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, fingerprint, null);
			try (DownloadClient client = DownloadClient.createAsync(connectionInfo, null, ignored -> CompletableFuture.completedFuture(false)).get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
				CompletableFuture<Path> first = client.downloadSmallObject("hash".getBytes(StandardCharsets.UTF_8), directory.resolve("first"), -1L, null);
				assertTrue(server.receivedRequest().await(AWAIT_SECONDS, TimeUnit.SECONDS));
				client.abortTransfers();
				assertThrows(Exception.class, () -> first.get(AWAIT_SECONDS, TimeUnit.SECONDS));
				int connectionsAtAbort = server.acceptedConnections();

				// The aborted gate stays shut: a submit after the abort is refused without opening a lane, so a cancelled run sends no requests.
				ExecutionException rejected = assertThrows(ExecutionException.class,
						() -> client.downloadSmallObject("hash".getBytes(StandardCharsets.UTF_8), directory.resolve("second"), -1L, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertEquals("Download aborted", rejected.getCause().getMessage());
				assertEquals(connectionsAtAbort, server.acceptedConnections(), "an aborted client must never reopen a lane");
			}
		}
	}

	@Test
	void sixConcurrentRequestsPipelineOntoOneConnection(@TempDir Path directory) throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		String fingerprint = NetUtils.getFingerprint(certificate);

		try (LeasingServer server = new LeasingServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, fingerprint, null);
			try (DownloadClient client = DownloadClient.createAsync(connectionInfo, null, ignored -> CompletableFuture.completedFuture(false)).get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
				List<CompletableFuture<Path>> downloads = new ArrayList<>();
				for (int i = 0; i < 6; i++) downloads.add(client.downloadSmallObject("hash".getBytes(StandardCharsets.UTF_8), directory.resolve("download-" + i), -1L, null));

				server.awaitRequests(6);
				assertEquals(1, server.acceptedConnections(), "a lane's window holds sixteen 4 MiB debits, so six requests share one connection");

				server.allowResponses(6);
				CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertEquals(1, server.acceptedConnections());
			}
		}
	}

	@Test
	void laneWindowOverflowOpensASecondConnection(@TempDir Path directory) throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		String fingerprint = NetUtils.getFingerprint(certificate);

		try (LeasingServer server = new LeasingServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, fingerprint, null);
			try (DownloadClient client = DownloadClient.createAsync(connectionInfo, null, ignored -> CompletableFuture.completedFuture(false)).get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
				int takesPerLane = (int) (NetUtils.PIPELINE_WINDOW_BYTES / NetUtils.WIRE_CHUNK_BYTES);
				List<CompletableFuture<Path>> downloads = new ArrayList<>();
				for (int i = 0; i < takesPerLane + 1; i++) downloads.add(client.downloadSmallObject("hash".getBytes(StandardCharsets.UTF_8), directory.resolve("download-" + i), -1L, null));

				server.awaitRequests(takesPerLane + 1);
				assertEquals(2, server.acceptedConnections(), "the request past one lane's window opens the next lane");

				server.allowResponses(takesPerLane + 1);
				CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}
		}
	}

	@Test
	void theLanePoolCapsAtFiveConnections(@TempDir Path directory) throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		String fingerprint = NetUtils.getFingerprint(certificate);

		try (LeasingServer server = new LeasingServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, fingerprint, null);
			try (DownloadClient client = DownloadClient.createAsync(connectionInfo, null, ignored -> CompletableFuture.completedFuture(false)).get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
				int poolBound = (int) (DownloadClient.LANES * (NetUtils.PIPELINE_WINDOW_BYTES / NetUtils.WIRE_CHUNK_BYTES));
				List<CompletableFuture<Path>> downloads = new ArrayList<>();
				for (int i = 0; i < poolBound + 1; i++) downloads.add(client.downloadSmallObject("hash".getBytes(StandardCharsets.UTF_8), directory.resolve("download-" + i), -1L, null));

				server.awaitRequests(poolBound);
				assertEquals(5, server.acceptedConnections(), "5 lanes × 16 take debits cap the in-flight requests; the request past the bound waits");

				server.allowResponses(poolBound + 1);
				CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertEquals(5, server.acceptedConnections());
			}
		}
	}

	/**
	 * The lane-wedge recovery pin. A peer that stays connected but stops reading may only park its own lane's writer
	 * task: the server fully serves the first connection it accepts and never reads any later one, with a listen-side
	 * receive buffer set before bind so the wedge's kernel window is as small as the stack allows. The wedged lanes
	 * carry document takes, whose heads are as big as their keys: documents debit one 4 MiB chunk each, so a lane's
	 * window holds exactly sixteen, and sixteen 256 KB-key heads are 4 MB of queued heads per wedge - past the ~1.8 MB
	 * this class of kernel absorbs for a non-reading loopback peer (the client send buffer autotunes past whatever the
	 * window admits), so the unfixed inline head flush inside the pool lock parks the whole client and the preemptive
	 * timeout fails the pin instead of hanging CI. While the wedges hold, exactly the served lane's window completes -
	 * proof the pool lock stayed free - and killing the wedges fails their unwritten takes; documents have no retry
	 * ladder, so the pin re-issues each failed fetch once and requires every body to land byte-exact on a fresh lane.
	 */
	@Test
	void aWedgedPeerParksOnlyItsOwnLaneAndItsTakesRecoverOntoFreshLanes(@TempDir Path directory) throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		String fingerprint = NetUtils.getFingerprint(certificate);
		int requestsPerLane = (int) (NetUtils.PIPELINE_WINDOW_BYTES / NetUtils.WIRE_CHUNK_BYTES);
		int documents = DownloadClient.LANES * requestsPerLane;
		int keyBytes = 256 * 1024;

		assertTimeoutPreemptively(Duration.ofSeconds(240), () -> {
			try (WedgeServer server = new WedgeServer(keyPair, certificate)) {
				StringBuilder key = new StringBuilder(keyBytes);
				for (int i = 0; i < keyBytes - 8; i++) key.append((char) ('a' + (i % 26)));
				String keyBase = key.toString();
				List<String> keys = new ArrayList<>();
				List<byte[]> bodies = new ArrayList<>();
				for (int i = 0; i < documents; i++) {
					byte[] body = ("document-" + i).getBytes(StandardCharsets.UTF_8);
					String documentKey = keyBase + String.format(Locale.ROOT, "%08x", i);
					server.store().put(documentKey, body);
					keys.add(documentKey);
					bodies.add(body);
				}

				ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
						new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, fingerprint, null);
				List<CompletableFuture<DocumentFetch>> downloads = new ArrayList<>();
				try (DownloadClient client = DownloadClient.createAsync(connectionInfo, null, ignored -> CompletableFuture.completedFuture(false)).get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
					// Nothing answers until every submit is booked: the served lane takes the first sixteen fetches and
					// each later lane takes sixteen more, every one of those lanes a wedge the server never reads.
					for (int i = 0; i < documents; i++)
						downloads.add(client.downloadDocument(keys.get(i).getBytes(StandardCharsets.UTF_8), directory.resolve("document-" + i), null, (IntConsumer) null));

					server.awaitAccepted(DownloadClient.LANES);
					server.awaitReadRequests(requestsPerLane);
					assertEquals(DownloadClient.LANES, server.acceptedConnections(), "one lane opens per sixteen unsettled documents, up to the pool cap");

					// The served lane's window settles while every wedge holds: the parked writes never touched the pool lock.
					server.openResponses();
					long deadline = System.currentTimeMillis() + 60_000;
					while (settled(downloads) < requestsPerLane && System.currentTimeMillis() < deadline) Thread.sleep(20);
					assertEquals(requestsPerLane, settled(downloads), "exactly the served lane's window completes while the wedges hold");

					// Killing the wedges fails their unwritten takes; documents have no retry ladder, so the pin waits
					// for the deaths to surface, re-issues each failed fetch once - onto fresh lanes the server now
					// serves - and requires every fetch to complete.
					server.killWedges();
					long drained = System.currentTimeMillis() + 30_000;
					while (downloads.stream().anyMatch(future -> !future.isDone()) && System.currentTimeMillis() < drained) Thread.sleep(20);
					for (int i = 0; i < documents; i++) {
						if (downloads.get(i).isCompletedExceptionally()) {
							final int index = i;
							downloads.set(i, client.downloadDocument(keys.get(index).getBytes(StandardCharsets.UTF_8), directory.resolve("document-" + index), null, (IntConsumer) null));
						}
					}
					CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
				}
				for (int i = 0; i < documents; i++) assertArrayEquals(bodies.get(i), Files.readAllBytes(directory.resolve("document-" + i)));
			}
		});
	}

	/** Futures that completed with a value, never with a failure - a settled wedge would break the exact-window split. */
	private static long settled(List<? extends CompletableFuture<?>> futures) {
		return futures.stream().filter(future -> future.isDone() && !future.isCompletedExceptionally()).count();
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

	private static X509Certificate issueCertificate(X500Name issuer, X500Name subject, String dnsName, KeyPair subjectKeyPair, KeyPair signingKeyPair,
			Instant notBefore, Instant notAfter) throws Exception {
		var builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.ONE, Date.from(notBefore), Date.from(notAfter), subject, subjectKeyPair.getPublic());
		if (dnsName != null) builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, dnsName)));
		var signer = new JcaContentSignerBuilder("SHA256WithRSA").build(signingKeyPair.getPrivate());
		return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
	}

	private record HttpRequest(String path, String range) {}

	private static HttpRequest readRequest(InputStream in) throws IOException {
		ByteArrayOutputStream head = new ByteArrayOutputStream(512);
		final byte[] terminator = {'\r', '\n', '\r', '\n'};
		int matched = 0;
		while (matched < 4) {
			int read = in.read();
			if (read < 0) throw new EOFException("Connection ended inside a request head");
			head.write(read);
			matched = read == terminator[matched] ? matched + 1 : read == terminator[0] ? 1 : 0;
		}
		String[] lines = head.toString(StandardCharsets.UTF_8).split("\r\n", -1);
		String[] requestLine = lines[0].split(" ");
		if (requestLine.length != 3 || !requestLine[0].equals("GET")) return null;
		String range = null;
		for (int i = 1; i < lines.length - 1; i++) {
			int colon = lines[i].indexOf(':');
			if (colon <= 0) continue;
			if (lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("range")) range = lines[i].substring(colon + 1).trim();
		}
		return new HttpRequest(requestLine[1], range);
	}

	private static void respond(BufferedOutputStream out, String status, byte[] body, String... headers) throws IOException {
		StringBuilder head = new StringBuilder(128);
		head.append("HTTP/1.1 ").append(status).append("\r\n");
		head.append("Content-Length: ").append(body.length).append("\r\n");
		for (String header : headers)
			head.append(header).append("\r\n");
		head.append("\r\n");
		out.write(head.toString().getBytes(StandardCharsets.UTF_8));
		out.write(body);
		out.flush();
	}

	private static final class LeasingServer implements AutoCloseable {
		private final ServerSocket server;
		private final SSLContext context;
		private final ExecutorService executor = Executors.newCachedThreadPool();
		private final List<SSLSocket> sockets = new CopyOnWriteArrayList<>();
		private final AtomicInteger acceptedConnections = new AtomicInteger();
		private final AtomicInteger requestsArrived = new AtomicInteger();
		private final CountDownLatch receivedRequest = new CountDownLatch(1);
		private final Semaphore responsePermits = new Semaphore(0);
		private volatile boolean closed;

		LeasingServer(KeyPair keyPair, X509Certificate certificate) throws Exception {
			context = serverContext(keyPair, certificate);
			server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
			executor.execute(this::acceptConnections);
		}

		int port() {
			return server.getLocalPort();
		}

		int acceptedConnections() {
			return acceptedConnections.get();
		}

		CountDownLatch receivedRequest() {
			return receivedRequest;
		}

		/** Waits for the server to have read that many requests; pipelined requests are all read before any permit is granted. */
		void awaitRequests(int count) throws InterruptedException {
			long deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1000L;
			while (requestsArrived.get() < count && System.currentTimeMillis() < deadline) Thread.sleep(10);
			assertTrue(requestsArrived.get() >= count, "expected " + count + " requests, saw " + requestsArrived.get());
		}

		void allowResponses(int count) {
			responsePermits.release(count);
		}

		private void acceptConnections() {
			while (!closed) {
				try {
					SSLSocket socket = MagicTls.accept(server, context);
					sockets.add(socket);
					acceptedConnections.incrementAndGet();
					executor.execute(() -> serve(socket));
				} catch (IOException e) {
					if (!closed) return;
				}
			}
		}

		private void serve(SSLSocket socket) {
			// The read loop never waits on a response, so pipelined requests are all read the moment they arrive; responses serialize on one thread per connection, in read order.
			ExecutorService responder = Executors.newSingleThreadExecutor();
			try {
				socket.setEnabledProtocols(new String[]{"TLSv1.3"});
				socket.startHandshake();
				BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
				BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
				while (!closed && !socket.isClosed()) {
					HttpRequest request = readRequest(in);
					if (request == null) return;
					receivedRequest.countDown();
					requestsArrived.incrementAndGet();
					responder.execute(() -> {
						try {
							responsePermits.acquire();
							respond(out, "200 OK", new byte[0]);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						} catch (IOException ignored) {
						}
					});
				}
			} catch (IOException ignored) {
			} finally {
				responder.shutdownNow();
				try {
					socket.close();
				} catch (IOException ignored) {
				}
			}
		}

		@Override
		public void close() throws Exception {
			closed = true;
			server.close();
			responsePermits.release(64);
			for (SSLSocket socket : sockets) socket.close();
			executor.shutdownNow();
		}
	}

	/**
	 * A two-faced contract server for the wedge pin: the first accepted connection is served fully (every stored
	 * document answered 200 with its body, responses held until released so the pool settles nothing early), and every
	 * later connection is accepted and handshaken but never read, so the client's writer task parks on it. The listen
	 * socket's receive buffer is set before bind and inherited by accepted sockets, keeping the wedge's advertised
	 * window at the stack's floor - the park itself is guaranteed by the queued head volume outrunning what any kernel
	 * absorbs for a non-reading peer, not by kernel default sizes.
	 */
	private static final class WedgeServer implements AutoCloseable {
		private final ServerSocket server;
		private final SSLContext context;
		private final ExecutorService executor = Executors.newCachedThreadPool();
		private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();
		private final List<SSLSocket> wedged = new ArrayList<>();
		private final AtomicInteger acceptedConnections = new AtomicInteger();
		private final AtomicInteger readRequests = new AtomicInteger();
		private final Semaphore responsePermits = new Semaphore(0);
		private final AtomicBoolean holdingResponses = new AtomicBoolean(true);
		private final AtomicBoolean wedging = new AtomicBoolean(true);
		private volatile boolean closed;

		WedgeServer(KeyPair keyPair, X509Certificate certificate) throws Exception {
			context = serverContext(keyPair, certificate);
			server = new ServerSocket();
			// Must precede bind: accepted sockets inherit it, which is what makes the wedge's write park deterministic.
			server.setReceiveBufferSize(32 * 1024);
			server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);
			executor.execute(this::acceptConnections);
		}

		int port() {
			return server.getLocalPort();
		}

		int acceptedConnections() {
			return acceptedConnections.get();
		}

		int readRequests() {
			return readRequests.get();
		}

		ConcurrentHashMap<String, byte[]> store() {
			return store;
		}

		void awaitAccepted(int count) throws InterruptedException {
			long deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1000L;
			while (acceptedConnections.get() < count && System.currentTimeMillis() < deadline) Thread.sleep(10);
			assertTrue(acceptedConnections.get() >= count, "expected " + count + " accepted connections, saw " + acceptedConnections.get());
		}

		void awaitReadRequests(int count) throws InterruptedException {
			long deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1000L;
			while (readRequests.get() < count && System.currentTimeMillis() < deadline) Thread.sleep(10);
			assertTrue(readRequests.get() >= count, "expected " + count + " read requests, saw " + readRequests.get());
		}

		/** Releases the held responses; every later connection is answered immediately. */
		void openResponses() {
			holdingResponses.set(false);
			responsePermits.release(NetUtils.PIPELINE_MAX_REQUESTS);
		}

		/** Stops wedging new connections, then closes every wedged one: their parked writers die and failPending frees the takes. */
		void killWedges() {
			wedging.set(false);
			synchronized (wedged) {
				for (SSLSocket socket : wedged) {
					try {
						socket.close();
					} catch (IOException ignored) {
					}
				}
			}
		}

		private void acceptConnections() {
			while (!closed) {
				try {
					SSLSocket socket = MagicTls.accept(server, context);
					System.out.println("[diag] accepted conn #" + (acceptedConnections.get() + 1) + " wedging=" + wedging.get());
					if (wedging.get() && acceptedConnections.incrementAndGet() > 1) {
						// Accepted and handshaken, never read: the wedge, kept open until killWedges.
						synchronized (wedged) {
							wedged.add(socket);
						}
						continue;
					}
					executor.execute(() -> serve(socket));
				} catch (IOException e) {
					if (!closed) return;
				}
			}
		}

		private void serve(SSLSocket socket) {
			// The read loop never waits on a response (a responder thread holds it instead), so pipelined requests are all read the moment they arrive.
			ExecutorService responder = Executors.newSingleThreadExecutor();
			try {
				BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
				BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
				while (!closed && !socket.isClosed()) {
					HttpRequest request;
					try {
						request = readRequest(in);
					} catch (EOFException ended) {
						return;
					}
					if (request == null) return;
					int read = readRequests.incrementAndGet();
					if (read > NetUtils.PIPELINE_MAX_REQUESTS) System.out.println("[diag] conn-read #" + read + ": " + request.path());
					responder.execute(() -> {
						try {
							if (holdingResponses.get()) responsePermits.acquire();
							answer(out, request);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						} catch (IOException ignored) {
						}
					});
				}
			} catch (IOException ignored) {
			} finally {
				responder.shutdownNow();
				try {
					socket.close();
				} catch (IOException ignored) {
				}
			}
		}

		private void answer(BufferedOutputStream out, HttpRequest request) throws IOException {
			byte[] content = request.path().length() > 1 ? store.get(request.path().substring(1)) : null;
			if (content == null) {
				respond(out, "404 Not Found", new byte[0]);
				return;
			}
			respond(out, "200 OK", content);
		}

		@Override
		public void close() throws Exception {
			closed = true;
			server.close();
			responsePermits.release(NetUtils.PIPELINE_MAX_REQUESTS);
			synchronized (wedged) {
				for (SSLSocket socket : wedged) socket.close();
			}
			executor.shutdownNow();
		}
	}

	private static final class TransferServer implements AutoCloseable {
		private final ServerSocket server;
		private final SSLContext context;
		private final ExecutorService executor = Executors.newSingleThreadExecutor();
		private final AtomicInteger acceptedConnections = new AtomicInteger();
		private final AtomicInteger heartbeats = new AtomicInteger();
		private final List<String> requests = new CopyOnWriteArrayList<>();
		private final CompletableFuture<Integer> earlyApplicationByte = new CompletableFuture<>();
		private volatile SSLSocket socket;
		private volatile boolean closed;

		TransferServer(KeyPair keyPair, X509Certificate certificate) throws Exception {
			context = serverContext(keyPair, certificate);
			server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
			executor.execute(this::serve);
		}

		int port() {
			return server.getLocalPort();
		}

		int acceptedConnections() {
			return acceptedConnections.get();
		}

		int heartbeats() {
			return heartbeats.get();
		}

		List<String> requests() {
			return requests;
		}

		CompletableFuture<Integer> earlyApplicationByte() {
			return earlyApplicationByte;
		}

		private void serve() {
			try {
				socket = MagicTls.accept(server, context);
				acceptedConnections.incrementAndGet();
				// The early-byte probe may catch the first heartbeat's first byte when the trust ladder resolves fast; the pushback hands it back to the request parser.
				PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(socket.getInputStream()));
				BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());

				socket.setSoTimeout(300);
				int early;
				try {
					early = in.read();
				} catch (SocketTimeoutException e) {
					early = -1;
				}
				earlyApplicationByte.complete(early);
				if (early >= 0) in.unread(early);

				socket.setSoTimeout(0);
				while (!closed && !socket.isClosed()) {
					HttpRequest request;
					try {
						request = readRequest(in);
					} catch (EOFException ended) {
						return;
					}
					if (request == null) return;
					if (request.path().equals("/head")) {
						heartbeats.incrementAndGet();
						respond(out, "200 OK", new byte[0]);
						continue;
					}
					requests.add(request.path());
					respond(out, "200 OK", new byte[0]);
				}
			} catch (Exception e) {
				if (!earlyApplicationByte.isDone()) earlyApplicationByte.completeExceptionally(e);
			} finally {
				try {
					if (socket != null) socket.close();
				} catch (IOException ignored) {
				}
			}
		}

		@Override
		public void close() throws Exception {
			closed = true;
			if (socket != null) socket.close();
			server.close();
			executor.shutdownNow();
		}
	}
}
