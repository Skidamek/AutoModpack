package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedInputStream;
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
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
	void pinsPassOrDeferAndOnlyTheLadderRecovers() throws Exception {
		X509Certificate accepted = NetUtils.selfSign(NetUtils.generateKeyPair());
		X509Certificate changed = NetUtils.selfSign(NetUtils.generateKeyPair());
		String fingerprint = NetUtils.getFingerprint(accepted);

		// The exact pin passes the leaf straight through; a changed leaf defers quietly - the handshake never
		// hard-fails, the ladder recovers it through a published fingerprint or ends in a pin mismatch.
		// The bare checkServerTrusted(chain, authType) variant keys its deferral on null.
		var configuredTrust = new CustomizableTrustManager.SessionTrust("origin.example:25565", fingerprint);
		var configuredManager = new CustomizableTrustManager(configuredTrust, null);
		assertDoesNotThrow(() -> configuredManager.checkServerTrusted(new X509Certificate[]{accepted}, "RSA"));
		assertNull(configuredManager.getDeferredCertificate(null));
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

		// A published fingerprint recovers a rotated leaf: the session's pin follows it.
		configuredTrust.recover(changed);
		var recoveredManager = new CustomizableTrustManager(configuredTrust, null);
		assertDoesNotThrow(() -> recoveredManager.checkServerTrusted(new X509Certificate[]{changed}, "RSA"));
		assertNull(recoveredManager.getDeferredCertificate(null));
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
	void abortTransfersFailsInFlightDownloadAndAllowsARetry(@TempDir Path directory) throws Exception {
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
				server.allowResponses(2);
				assertEquals(directory.resolve("second"), client.downloadSmallObject("hash".getBytes(StandardCharsets.UTF_8), directory.resolve("second"), -1L, null).get(AWAIT_SECONDS, TimeUnit.SECONDS));
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
				assertEquals(1, server.acceptedConnections(), "a lane holds eight in flight, so six requests share one connection");

				server.allowResponses(6);
				CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertEquals(1, server.acceptedConnections());
			}
		}
	}

	@Test
	void laneDepthOverflowOpensASecondConnection(@TempDir Path directory) throws Exception {
		KeyPair keyPair = NetUtils.generateKeyPair();
		X509Certificate certificate = NetUtils.selfSign(keyPair);
		String fingerprint = NetUtils.getFingerprint(certificate);

		try (LeasingServer server = new LeasingServer(keyPair, certificate)) {
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
					new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, fingerprint, null);
			try (DownloadClient client = DownloadClient.createAsync(connectionInfo, null, ignored -> CompletableFuture.completedFuture(false)).get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
				List<CompletableFuture<Path>> downloads = new ArrayList<>();
				for (int i = 0; i < 9; i++) downloads.add(client.downloadSmallObject("hash".getBytes(StandardCharsets.UTF_8), directory.resolve("download-" + i), -1L, null));

				server.awaitRequests(9);
				assertEquals(2, server.acceptedConnections(), "the ninth request passes the depth of eight and opens the next lane");

				server.allowResponses(9);
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
				List<CompletableFuture<Path>> downloads = new ArrayList<>();
				for (int i = 0; i < 41; i++) downloads.add(client.downloadSmallObject("hash".getBytes(StandardCharsets.UTF_8), directory.resolve("download-" + i), -1L, null));

				server.awaitRequests(40);
				assertEquals(5, server.acceptedConnections(), "5 lanes × 8 slots cap the in-flight requests; request 41 waits");

				server.allowResponses(41);
				CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertEquals(5, server.acceptedConnections());
			}
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
