package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.airlift.compress.zstd.ZstdOutputStream;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;

/**
 * The conditional and ranged client behavior over a real TLS server speaking the HTTP contract: documents answered
 * 304 on a matching expected hash, the hash-compare verdict when the server ignores the condition, object resume
 * through a ranged request, stale-range verdicts, redirects, and the bearer secret riding every request.
 */
class ConditionalFetchTest {
	/** Generous bound for loopback handshakes that complete in milliseconds when warm; cold CI runners have blown past five seconds here. */
	private static final int AWAIT_SECONDS = 20;

	@Test
	void nullSecretSendsNoAuthorizationHeader(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.store.put("head", "head-document".getBytes(StandardCharsets.UTF_8));
			try (DownloadClient client = client(server, null)) {
				client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), directory.resolve("head"), null, (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}
			assertNull(server.firstAuthorization.get(AWAIT_SECONDS, TimeUnit.SECONDS));
		}
	}

	@Test
	void heldSecretAuthenticatesEveryRequest(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.requireAuth.set(true);
			server.bearerSecret = "a-bearer-secret";
			server.store.put("head", "head-document".getBytes(StandardCharsets.UTF_8));
			try (DownloadClient client = client(server, server.bearerSecret)) {
				client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), directory.resolve("head"), null, (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}
			assertEquals("Bearer a-bearer-secret", server.firstAuthorization.get(AWAIT_SECONDS, TimeUnit.SECONDS));
		}
	}

	@Test
	void missingSecretIsRejectedWith401WithoutARetry(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.requireAuth.set(true);
			server.bearerSecret = "a-bearer-secret";
			server.store.put("head", "head-document".getBytes(StandardCharsets.UTF_8));
			try (DownloadClient client = client(server, null)) {
				var future = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), directory.resolve("head"), null, (IntConsumer) null);
				var thrown = assertThrows(ExecutionException.class, () -> future.get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertInstanceOf(UnauthorizedException.class, thrown.getCause());
			}
			assertEquals(1, server.requests.size());
		}
	}

	@Test
	void cooperatingServerAnswersUnchangedForMatchingHeadAndJournal(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] head = "head-document".getBytes(StandardCharsets.UTF_8);
			byte[] journal = "journal-line\n".getBytes(StandardCharsets.UTF_8);
			server.store.put("head", head);
			server.store.put("journal", journal);
			try (DownloadClient client = client(server, "test-secret")) {
				var unchangedHead = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), directory.resolve("head"), HashUtils.sha1(head), (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertNull(unchangedHead.path());
				assertTrue(unchangedHead.unchanged());

				var unchangedJournal = client.downloadDocument("journal".getBytes(StandardCharsets.UTF_8), directory.resolve("journal"), HashUtils.sha1(journal), (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertNull(unchangedJournal.path());
				assertTrue(unchangedJournal.unchanged());
				assertFalse(Files.exists(directory.resolve("head")));
				assertFalse(Files.exists(directory.resolve("journal")));
			}
		}
	}

	@Test
	void staleDocumentStreamsAndStaysFetched(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] oldHead = "old-head-document".getBytes(StandardCharsets.UTF_8);
			byte[] newHead = "new-and-longer-head-document".getBytes(StandardCharsets.UTF_8);
			server.store.put("head", oldHead);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("head");
				var first = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), destination, HashUtils.sha1(oldHead), (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertTrue(first.unchanged());

				server.store.put("head", newHead);
				var second = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), destination, HashUtils.sha1(oldHead), (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertFalse(second.unchanged());
				assertEquals(destination, second.path());
				assertArrayEquals(newHead, Files.readAllBytes(destination));
			}
		}
	}

	@Test
	void nonCooperatingServerStillReadsUnchangedThroughTheBodyHash(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.cooperate.set(false);
			byte[] head = "head-document-that-the-server-streams-anyway".getBytes(StandardCharsets.UTF_8);
			server.store.put("head", head);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("head");
				// The host ignored the conditional and sent the full body; the hash-compare is the ground truth.
				var fetch = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), destination, HashUtils.sha1(head), (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertEquals(destination, fetch.path());
				assertTrue(fetch.unchanged());
				assertArrayEquals(head, Files.readAllBytes(destination));
			}
		}
	}

	@Test
	void zstdDocumentDecodesToIdentityBytes(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.compressDocuments.set(true);
			byte[] head = "a-repetitive-head-document-that-compresses-well\n".repeat(8).getBytes(StandardCharsets.UTF_8);
			server.store.put("head", head);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("head");
				var fetch = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), destination, null, (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertEquals(destination, fetch.path());
				assertFalse(fetch.unchanged());
				assertArrayEquals(head, Files.readAllBytes(destination));
				assertTrue(server.sawAcceptEncoding.get(), "the client offered zstd on document GETs");
				assertTrue(server.lastResponseZstd.get(), "the server answered with a compressed body");
			}
		}
	}

	@Test
	void chunkedZstdBodiesDecodeAndKeepTheConnectionAligned(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.compressDocuments.set(true);
			server.chunkedDocuments.set(true);
			byte[] head = "a-repetitive-head-document-streamed-in-chunks\n".repeat(8).getBytes(StandardCharsets.UTF_8);
			byte[] journal = "a-repetitive-journal-document-streamed-in-chunks\n".repeat(8).getBytes(StandardCharsets.UTF_8);
			server.store.put("head", head);
			server.store.put("journal", journal);
			try (DownloadClient client = client(server, "test-secret")) {
				Path headDestination = directory.resolve("head");
				client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), headDestination, null, (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertArrayEquals(head, Files.readAllBytes(headDestination));
				// The second body rides the same connection: exact chunk framing left the pipeline aligned behind it.
				Path journalDestination = directory.resolve("journal");
				client.downloadDocument("journal".getBytes(StandardCharsets.UTF_8), journalDestination, null, (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertArrayEquals(journal, Files.readAllBytes(journalDestination));
				assertEquals(1, server.connections.get(), "both chunked responses rode one connection");
			}
		}
	}

	@Test
	void gzipChunkedBodyWithLateTerminatorKeepsTheConnectionAligned(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.gzipDocuments.set(true);
			server.chunkedDocuments.set(true);
			server.delayFinalChunk.set(true);
			byte[] head = "a-repetitive-head-document-streamed-in-gzip-chunks\n".repeat(8).getBytes(StandardCharsets.UTF_8);
			byte[] journal = "a-repetitive-journal-document-streamed-in-gzip-chunks\n".repeat(8).getBytes(StandardCharsets.UTF_8);
			server.store.put("head", head);
			server.store.put("journal", journal);
			try (DownloadClient client = client(server, "test-secret")) {
				// gzip ends its stream before the chunked terminator reaches the wire; the next response rides the same
				// connection, so the frame drain decides whether alignment survives.
				Path headDestination = directory.resolve("head");
				client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), headDestination, null, (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertArrayEquals(head, Files.readAllBytes(headDestination));
				Path journalDestination = directory.resolve("journal");
				client.downloadDocument("journal".getBytes(StandardCharsets.UTF_8), journalDestination, null, (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertArrayEquals(journal, Files.readAllBytes(journalDestination));
				assertEquals(1, server.connections.get(), "both responses rode one connection past the late terminator");
			}
		}
	}

	@Test
	void zstdBodyHashIsTheGroundTruthWhenTheHostIgnoresTheCondition(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.compressDocuments.set(true);
			server.cooperate.set(false);
			byte[] head = "a-repetitive-head-document-that-compresses-well\n".repeat(8).getBytes(StandardCharsets.UTF_8);
			server.store.put("head", head);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("head");
				var fetch = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), destination, HashUtils.sha1(head), (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertEquals(destination, fetch.path());
				assertTrue(fetch.unchanged(), "the decoded body hash still reads as unchanged");
				assertArrayEquals(head, Files.readAllBytes(destination));
			}
		}
	}

	@Test
	void objectRequestsAreNeverCompressed(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.compressDocuments.set(true);
			byte[] object = "object-bytes-that-would-compress-but-must-not".getBytes(StandardCharsets.UTF_8);
			String sha1 = HashUtils.sha1(object);
			server.store.put(sha1, object);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("object");
				client.downloadFile(sha1.getBytes(StandardCharsets.UTF_8), destination, null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertArrayEquals(object, Files.readAllBytes(destination));
				assertFalse(server.lastResponseZstd.get(), "objects stay identity so Range and resume stay trivial");
			}
		}
	}

	@Test
	void aHostThatIgnoresTheEncodingServesIdentityUndecoded(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] head = "plain-identity-head-document".getBytes(StandardCharsets.UTF_8);
			server.store.put("head", head);
			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("head");
				client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), destination, null, (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertTrue(server.sawAcceptEncoding.get(), "the client offered zstd even to a host that ignores it");
				assertFalse(server.lastResponseZstd.get());
				assertArrayEquals(head, Files.readAllBytes(destination));
			}
		}
	}

	@Test
	void rangedRequestAppendsBehindTheStoredPartialAndPromotionVerifies(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] object = new byte[256 * 1024];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store.put(sha1, object);

			Path partial = directory.resolve("partial");
			Files.write(partial, Arrays.copyOf(object, 100_000));

			try (DownloadClient client = client(server, "test-secret")) {
				client.downloadFile(sha1.getBytes(StandardCharsets.UTF_8), partial, 100_000, null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}
			assertArrayEquals(object, Files.readAllBytes(partial));
			assertTrue(FileIntegrity.matches(partial, object.length, sha1));

			Path storeFile = directory.resolve("object.bin");
			VerifiedFileTransfer.promoteAtomic(partial, storeFile, object.length, sha1);
			assertTrue(Files.exists(storeFile));
		}
	}

	@Test
	void rangedRequestPastObjectSizeReadsAsStaleRange(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] object = "complete-object-bytes".getBytes(StandardCharsets.UTF_8);
			String sha1 = HashUtils.sha1(object);
			server.store.put(sha1, object);

			Path destination = directory.resolve("partial");
			Files.write(destination, object);

			try (DownloadClient client = client(server, "test-secret")) {
				var future = client.downloadFile(sha1.getBytes(StandardCharsets.UTF_8), destination, object.length, null);
				assertThrows(ExecutionException.class, () -> future.get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertInstanceOf(StaleRangeException.class, rootCause(future));
			}
			assertArrayEquals(object, Files.readAllBytes(destination));
		}
	}

	@Test
	void mismatchedResumeStartReadsAsStaleRange(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] object = new byte[256 * 1024];
			new SecureRandom().nextBytes(object);
			String sha1 = HashUtils.sha1(object);
			server.store.put(sha1, object);
			Path partial = directory.resolve("partial");
			Files.write(partial, Arrays.copyOf(object, 100_000));
			server.lieAboutResumeStart.set(true);

			try (DownloadClient client = client(server, "test-secret")) {
				var future = client.downloadFile(sha1.getBytes(StandardCharsets.UTF_8), partial, 100_000, null);
				assertThrows(ExecutionException.class, () -> future.get(AWAIT_SECONDS, TimeUnit.SECONDS));
				assertInstanceOf(StaleRangeException.class, rootCause(future));
			}
		}
	}

	@Test
	void redirectIsFollowedToTheTarget(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			byte[] object = "redirected-object-bytes".getBytes(StandardCharsets.UTF_8);
			String sha1 = HashUtils.sha1(object);
			server.store.put(sha1, object);
			server.redirects.put("/journal", "/objects/" + sha1);

			try (DownloadClient client = client(server, "test-secret")) {
				Path destination = directory.resolve("journal");
				var fetch = client.downloadDocument("journal".getBytes(StandardCharsets.UTF_8), destination, null, (IntConsumer) null).get(AWAIT_SECONDS, TimeUnit.SECONDS);
				assertEquals(destination, fetch.path());
				assertFalse(fetch.unchanged());
				assertArrayEquals(object, Files.readAllBytes(destination));
			}
		}
	}

	@Test
	void redirectLoopFailsAfterThreeHops(@TempDir Path directory) throws Exception {
		try (ContractServer server = new ContractServer()) {
			server.redirects.put("/head", "/head");
			try (DownloadClient client = client(server, "test-secret")) {
				var future = client.downloadDocument("head".getBytes(StandardCharsets.UTF_8), directory.resolve("head"), null, (IntConsumer) null);
				assertThrows(ExecutionException.class, () -> future.get(AWAIT_SECONDS, TimeUnit.SECONDS));
			}
			// Three re-issues follow the initial request; the fourth redirect answer fails the fetch.
			assertEquals(4, server.requests.size());
		}
	}

	private static Throwable rootCause(CompletableFuture<?> future) {
		try {
			future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception e) {
			Throwable cause = e.getCause();
			while (cause.getCause() != null)
				cause = cause.getCause();
			return cause;
		}
		throw new AssertionError("The future was expected to fail");
	}

	private static DownloadClient client(ContractServer server, String secret) throws Exception {
		ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(InetSocketAddress.createUnresolved("127.0.0.1", 25565),
				new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), ModpackConnectionMode.MAGIC, server.fingerprint(), null);
		return DownloadClient.createAsync(connectionInfo, secret, ignored -> CompletableFuture.completedFuture(false)).get(AWAIT_SECONDS, TimeUnit.SECONDS);
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

	/** One TLS server speaking the HTTP contract side against an in-memory object store. */
	static final class ContractServer implements AutoCloseable {
		private final ServerSocket server;
		private final SSLContext context;
		private final ExecutorService executor = Executors.newCachedThreadPool();
		final Map<String, byte[]> store = new ConcurrentHashMap<>();
		final Map<String, String> redirects = new ConcurrentHashMap<>();
		final AtomicBoolean cooperate = new AtomicBoolean(true);
		final AtomicBoolean requireAuth = new AtomicBoolean(false);
		final AtomicBoolean lieAboutResumeStart = new AtomicBoolean(false);
		final AtomicBoolean compressDocuments = new AtomicBoolean(false);
		final AtomicBoolean gzipDocuments = new AtomicBoolean(false);
		final AtomicBoolean chunkedDocuments = new AtomicBoolean(false);
		final AtomicBoolean delayFinalChunk = new AtomicBoolean(false);
		final AtomicBoolean lastResponseZstd = new AtomicBoolean(false);
		final AtomicBoolean sawAcceptEncoding = new AtomicBoolean(false);
		final AtomicInteger connections = new AtomicInteger();
		final CompletableFuture<String> firstAuthorization = new CompletableFuture<>();
		final List<String> requests = new CopyOnWriteArrayList<>();
		private final AtomicBoolean secretRecorded = new AtomicBoolean();
		private final X509Certificate certificate;
		volatile String bearerSecret;
		private volatile CountDownLatch expectedPipeline;
		private volatile boolean pipelineArrived;
		private volatile long responseDelayMillis;
		private volatile boolean closed;

		ContractServer() throws Exception {
			KeyPair keyPair = NetUtils.generateKeyPair();
			certificate = selfSigned(keyPair);
			context = serverContext(keyPair, certificate);
			server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
			executor.execute(this::acceptConnections);
		}

		private static X509Certificate selfSigned(KeyPair keyPair) throws Exception {
			X500Name subject = new X500Name("CN=AutoModpack ConditionalFetchTest");
			var builder = new JcaX509v3CertificateBuilder(subject, BigInteger.ONE, Date.from(Instant.now().minusSeconds(60)),
					Date.from(Instant.now().plusSeconds(3600)), subject, keyPair.getPublic());
			return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate())));
		}

		int port() {
			return server.getLocalPort();
		}

		String fingerprint() throws Exception {
			return NetUtils.getFingerprint(certificate);
		}

		Map<String, byte[]> store() {
			return store;
		}

		/** Before its first response the server waits for this many requests to arrive, proving the client had them all in flight. */
		void expectPipeline(int count) {
			expectedPipeline = new CountDownLatch(count);
		}

		boolean pipelineArrived() {
			return pipelineArrived;
		}

		void setResponseDelayMillis(long delay) {
			responseDelayMillis = delay;
		}

		private void acceptConnections() {
			while (!closed) {
				try {
					SSLSocket socket = MagicTls.accept(server, context);
					connections.incrementAndGet();
					executor.execute(() -> serve(socket));
				} catch (IOException e) {
					if (!closed) return;
				}
			}
		}

		private void serve(SSLSocket socket) {
			// Responses serialize on one thread per connection, in read order; the read loop never waits on a response, so pipelined requests are all read the moment they arrive.
			ExecutorService responder = Executors.newSingleThreadExecutor();
			try {
				socket.setEnabledProtocols(new String[]{"TLSv1.3"});
				socket.startHandshake();
				BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
				BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
				AtomicBoolean answering = new AtomicBoolean();
				while (!closed && !socket.isClosed()) {
					Request request;
					try {
						request = parseRequest(readHead(in));
					} catch (EOFException ended) {
						return;
					}
					if (request == null) return;
					requests.add(request.path);
					if (secretRecorded.compareAndSet(false, true)) firstAuthorization.complete(request.authorization);
					if (request.acceptEncoding != null) sawAcceptEncoding.set(true);
					CountDownLatch pipeline = expectedPipeline;
					if (pipeline != null) pipeline.countDown();
					responder.execute(() -> answer(socket, out, request, pipeline, answering));
				}
			} catch (Exception ignored) {
			} finally {
				responder.shutdownNow();
				try {
					socket.close();
				} catch (IOException ignored) {
				}
			}
		}

		private void answer(SSLSocket socket, BufferedOutputStream out, Request request, CountDownLatch pipeline, AtomicBoolean answering) {
			try {
				if (answering.compareAndSet(false, true) && pipeline != null) {
					pipeline.await(10, TimeUnit.SECONDS);
					pipelineArrived = pipeline.getCount() == 0;
				}
				if (responseDelayMillis > 0) Thread.sleep(responseDelayMillis);
				if (requireAuth.get() && !("Bearer " + bearerSecret).equals(request.authorization)) {
					respond(out, "401 Unauthorized", new byte[0], "Connection: close");
					socket.close();
					return;
				}
				String location = redirects.get(request.path);
				if (location != null) {
					respond(out, "302 Found", new byte[0], "Location: " + location);
					return;
				}
				String key = routeKey(request.path);
				byte[] content = key == null ? null : store.get(key);
				if (content == null) {
					respond(out, "404 Not Found", new byte[0]);
					return;
				}
				if (!request.path.startsWith("/objects/") && request.ifNoneMatch != null && cooperate.get()
						&& HashUtils.sha1(content).equals(request.ifNoneMatch.replace("\"", ""))) {
					respond(out, "304 Not Modified", new byte[0]);
					return;
				}
				Long offset = parseRangeStart(request.range);
				if (offset != null && offset >= content.length) {
					respond(out, "416 Range Not Satisfiable", new byte[0], "Content-Range: bytes */" + content.length);
					return;
				}
				if (offset != null) {
					long start = lieAboutResumeStart.get() ? offset + 5 : offset;
					respond(out, "206 Partial Content", Arrays.copyOfRange(content, offset.intValue(), content.length),
							"Content-Range: bytes " + start + "-" + (content.length - 1) + "/" + content.length);
					return;
				}
				lastResponseZstd.set(false);
				if (compressDocuments.get() && !request.path.startsWith("/objects/") && request.acceptEncoding != null
						&& request.acceptEncoding.toLowerCase(Locale.ROOT).contains("zstd")) {
					lastResponseZstd.set(true);
					byte[] compressed = zstdCompress(content);
					if (chunkedDocuments.get()) respondChunked(out, "200 OK", compressed, 0, "Content-Encoding: zstd");
					else respond(out, "200 OK", compressed, "Content-Encoding: zstd");
					return;
				}
				if (gzipDocuments.get() && !request.path.startsWith("/objects/") && request.acceptEncoding != null) {
					byte[] compressed = gzipCompress(content);
					respondChunked(out, "200 OK", compressed, delayFinalChunk.get() ? 1500 : 0, "Content-Encoding: gzip");
					return;
				}
				respond(out, "200 OK", content);
			} catch (Exception ignored) {
			}
		}

		private static byte[] zstdCompress(byte[] content) throws IOException {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			try (ZstdOutputStream zstd = new ZstdOutputStream(buffer)) {
				zstd.write(content);
			}
			return buffer.toByteArray();
		}

		private static byte[] gzipCompress(byte[] content) throws IOException {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
				gzip.write(content);
			}
			return buffer.toByteArray();
		}

		/** The same body in chunked framing, cut into odd-sized frames so the de-framer sees several; the terminator may be held back past the body's end. */
		private static void respondChunked(BufferedOutputStream out, String status, byte[] body, long finalChunkDelayMillis, String... headers) throws IOException {
			StringBuilder head = new StringBuilder(128);
			head.append("HTTP/1.1 ").append(status).append("\r\n");
			head.append("Transfer-Encoding: chunked\r\n");
			for (String header : headers)
				head.append(header).append("\r\n");
			head.append("\r\n");
			out.write(head.toString().getBytes(StandardCharsets.UTF_8));
			for (int offset = 0; offset < body.length; offset += 37) {
				byte[] frame = Arrays.copyOfRange(body, offset, Math.min(offset + 37, body.length));
				out.write((Integer.toHexString(frame.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
				out.write(frame);
				out.write("\r\n".getBytes(StandardCharsets.UTF_8));
			}
			out.flush();
			if (finalChunkDelayMillis > 0) {
				try {
					Thread.sleep(finalChunkDelayMillis);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while holding the final chunk", e);
				}
			}
			out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
			out.flush();
		}

		private static String routeKey(String path) {
			if (path.equals("/head") || path.equals("/journal")) return path.substring(1);
			if (path.startsWith("/objects/")) return path.substring("/objects/".length());
			return null;
		}

		private static Long parseRangeStart(String range) {
			if (range == null || !range.startsWith("bytes=")) return null;
			String spec = range.substring("bytes=".length()).trim();
			int dash = spec.indexOf('-');
			if (dash <= 0) return null;
			try {
				return Long.parseLong(spec.substring(0, dash).trim());
			} catch (NumberFormatException e) {
				return null;
			}
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

		private record Request(String path, String authorization, String ifNoneMatch, String range, String acceptEncoding) {}

		private static Request parseRequest(String head) {
			String[] lines = head.split("\r\n", -1);
			String[] requestLine = lines[0].split(" ");
			if (requestLine.length != 3 || !requestLine[0].equals("GET")) return null;
			String authorization = null;
			String ifNoneMatch = null;
			String range = null;
			String acceptEncoding = null;
			for (int i = 1; i < lines.length - 1; i++) {
				int colon = lines[i].indexOf(':');
				if (colon <= 0) continue;
				String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
				String value = lines[i].substring(colon + 1).trim();
				if (name.equals("authorization")) authorization = value;
				else if (name.equals("if-none-match")) ifNoneMatch = value;
				else if (name.equals("range")) range = value;
				else if (name.equals("accept-encoding")) acceptEncoding = value;
			}
			return new Request(requestLine[1], authorization, ifNoneMatch, range, acceptEncoding);
		}

		private static String readHead(BufferedInputStream in) throws IOException {
			ByteArrayOutputStream head = new ByteArrayOutputStream(512);
			final byte[] terminator = {'\r', '\n', '\r', '\n'};
			int matched = 0;
			while (matched < 4) {
				int read = in.read();
				if (read < 0) throw new EOFException("Connection ended inside a request head");
				head.write(read);
				matched = read == terminator[matched] ? matched + 1 : read == terminator[0] ? 1 : 0;
			}
			return head.toString(StandardCharsets.UTF_8);
		}

		@Override
		public void close() throws Exception {
			closed = true;
			server.close();
			executor.shutdownNow();
		}
	}
}
