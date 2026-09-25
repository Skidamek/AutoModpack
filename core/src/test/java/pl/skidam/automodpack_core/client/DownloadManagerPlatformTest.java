package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;

/**
 * The platform path against a real HTTP server: platform-first priority (the transport never sees a file the platform
 * served), the 404 fallback to the host wire, and the same bytes in whatever the route. The transport-level tiling of
 * chunk edges is proven where it lives now, against the real client: DownloadObjectTest.
 */
class DownloadManagerPlatformTest {

	private static HttpServer server;
	private static final Map<String, byte[]> CONTENTS = new HashMap<>();
	/** Every request the resume-lab endpoints answered, so the tests can fail fast on the wire behavior after the run. */
	private static final List<ServedRequest> RESUME_REQUESTS = new ArrayList<>();
	/** Every /held request path in arrival order, so the dispatch-order test can read the wire instead of guessing. */
	private static final List<String> HELD_REQUESTS = new CopyOnWriteArrayList<>();
	/** Per-path gates: a /held request waits for its release, which is what holds a platform worker slot. */
	private static final Map<String, CountDownLatch> HELD_LATCHES = new ConcurrentHashMap<>();

	private record ServedRequest(String scenario, String range, String userAgent) {}

	@TempDir
	Path tempDir;

	private DataRootResolver.Layout layout;

	@BeforeAll
	static void startServer() throws IOException {
		CONTENTS.put("empty.bin", new byte[0]);
		CONTENTS.put("one-byte.txt", new byte[]{'x'});
		CONTENTS.put("chunk-under.bin", deterministic("chunk-under", 4_194_303));
		CONTENTS.put("chunk-exact.bin", deterministic("chunk-exact", 4_194_304));
		CONTENTS.put("chunk-over.bin", deterministic("chunk-over", 4_194_305));
		CONTENTS.put("multi-chunk.bin", deterministic("multi-chunk", 12_582_912));
		CONTENTS.put("validates-ünïcode.jar", deterministic("unicode", 4096));
		CONTENTS.put("resume.bin", deterministic("resume", 1_048_576));
		CONTENTS.put("held.bin", deterministic("held", 8192));
		CONTENTS.put("blocked-head.bin", deterministic("blocked-head", 16_384));
		// Without an executor every exchange would run on one dispatcher thread, and a held exchange would starve the rest.
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "platform-test-http");
			t.setDaemon(true);
			return t;
		}));
		server.createContext("/good", exchange -> {
			byte[] body = CONTENTS.get(exchange.getRequestURI().getPath().substring("/good/".length()));
			if (body == null) {
				exchange.sendResponseHeaders(404, -1);
			} else {
				exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
				if (body.length > 0) try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			}
			exchange.close();
		});
		server.createContext("/gzipped", exchange -> {
			String name = exchange.getRequestURI().getPath().substring("/gzipped/".length());
			byte[] body = CONTENTS.get(name);
			exchange.getResponseHeaders().set("Content-Encoding", "gzip");
			exchange.sendResponseHeaders(200, 0);
			try (GZIPOutputStream gzip = new GZIPOutputStream(exchange.getResponseBody())) {
				gzip.write(body);
			}
			exchange.close();
		});
		server.createContext("/resume-lab", exchange -> serveResumeLab(exchange));
		server.createContext("/held", exchange -> {
			String name = exchange.getRequestURI().getPath().substring("/held/".length());
			HELD_REQUESTS.add(name);
			CountDownLatch latch = HELD_LATCHES.get(name);
			if (latch != null) try {
				latch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			byte[] body = CONTENTS.get(name);
			exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
			if (body.length > 0) try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
			exchange.close();
		});
		server.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().set("Location", "/good/" + exchange.getRequestURI().getPath().substring("/redirect/".length()));
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.start();
	}

	/**
	 * The interrupted-download scenarios: the first (Range-less) attempt serves a prefix of the body and kills the
	 * connection mid-body; the retry's behavior depends on the scenario - a 206 from the requested offset, a 200 that
	 * ignores the Range, or a 416 that declares the partial stale.
	 */
	private static void serveResumeLab(HttpExchange exchange) throws IOException {
		byte[] body = CONTENTS.get("resume.bin");
		String range = exchange.getRequestHeaders().getFirst("Range");
		String scenario = exchange.getRequestURI().getPath().substring("/resume-lab/".length());
		synchronized (RESUME_REQUESTS) {
			RESUME_REQUESTS.add(new ServedRequest(scenario, range, exchange.getRequestHeaders().getFirst("User-Agent")));
		}
		if (range == null) {
			int prefix = "resumable".equals(scenario) ? body.length * 8 / 10 : body.length * 4 / 10;
			exchange.sendResponseHeaders(200, body.length);
			OutputStream out = exchange.getResponseBody();
			out.write(body, 0, prefix);
			out.flush();
			exchange.close(); // short of the declared length: the client loses the connection mid-body
			return;
		}
		long start = Long.parseLong(range.replace("bytes=", "").replace("-", ""));
		switch (scenario) {
			case "resumable" -> {
				exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + (body.length - 1) + "/" + body.length);
				exchange.sendResponseHeaders(206, body.length - start);
				exchange.getResponseBody().write(body, (int) start, body.length - (int) start);
			}
			case "range-ignored" -> {
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
			case "stale" -> exchange.sendResponseHeaders(416, -1);
			default -> throw new IOException("Unknown resume-lab scenario: " + scenario);
		}
		exchange.close();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	private static byte[] deterministic(String seed, int size) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) bytes[i] = (byte) (seed.charAt(i % seed.length()) + i);
		return bytes;
	}

	static List<String> platformServedFiles() {
		return List.of("empty.bin", "one-byte.txt", "chunk-under.bin", "chunk-exact.bin", "chunk-over.bin", "multi-chunk.bin", "validates-ünïcode.jar");
	}

	/** The platform serves the file; the host transport must never be asked for it. */
	@ParameterizedTest
	@MethodSource("platformServedFiles")
	void platformServesAndTheTransportStaysIdle(String name) throws Exception {
		byte[] expected = CONTENTS.get(name);
		FakeTransport transport = new FakeTransport(null);
		Path stored = downloadViaPlatform(name, name, "/good/" + name, transport);
		assertArrayEquals(expected, Files.readAllBytes(stored));
		assertEquals(List.of(), transport.fetches, "the host wire must stay idle while the platform serves");
	}

	@Test
	void gzippedPlatformBodiesDecodeToTheSameBytes() throws Exception {
		Path stored = downloadViaPlatform("gzipped.bin", "chunk-over.bin", "/gzipped/chunk-over.bin", new FakeTransport(null));
		assertArrayEquals(CONTENTS.get("chunk-over.bin"), Files.readAllBytes(stored));
	}

	/** The platform 404s; the same file arrives from the host wire behind the stored prefix. */
	@Test
	void platformMissFallsBackToTheHostWire() throws Exception {
		byte[] expected = CONTENTS.get("multi-chunk.bin");
		FakeTransport transport = new FakeTransport(expected);
		Path stored = downloadViaPlatform("fallback.bin", "multi-chunk.bin", "/missing/fallback.bin", transport);
		assertArrayEquals(expected, Files.readAllBytes(stored));
		assertEquals(1, transport.fetches.size(), "the burned budget falls through to exactly one host transfer, whose tiling is the transport's business");
	}

	/**
	 * Dispatch order is largest first regardless of enqueue order. Deterministic without racing the worker threads: held
	 * platform downloads occupy the whole worker budget, so the small file enqueues and the large one queues behind it;
	 * releasing exactly one holder frees exactly one worker slot, and that slot must go to the larger queued file. If
	 * the order ever regressed to enqueue order, the small file would take the slot and the large one would still be
	 * parked at its latch when the released slot's file arrives.
	 */
	@Test
	void dispatchesTheLargestQueuedFileFirstRegardlessOfEnqueueOrder() throws Exception {
		layout = new DataRootResolver.Layout(tempDir.resolve("data"));
		PlatformCache cache = PlatformCache.open(tempDir.resolve("platform-cache"));
		byte[] smallContent = CONTENTS.get("one-byte.txt");
		byte[] largeContent = CONTENTS.get("multi-chunk.bin");
		byte[] heldContent = CONTENTS.get("held.bin");
		List<String> holderNames = List.of("chunk-under.bin", "chunk-exact.bin", "chunk-over.bin", "held.bin", "resume.bin");
		long totalBytes = holderNames.size() * (long) heldContent.length + smallContent.length + largeContent.length;
		DownloadManager manager = new DownloadManager(totalBytes, layout, cache);
		manager.attachTransport(new FakeTransport(null)); // any host fetch would fail this test loudly
		for (String holder : holderNames) HELD_LATCHES.put(holder, new CountDownLatch(1));
		HELD_LATCHES.put("one-byte.txt", new CountDownLatch(1));
		HELD_LATCHES.put("multi-chunk.bin", new CountDownLatch(1));
		for (String holder : holderNames) enqueuePlatformDownload(manager, holder, CONTENTS.get(holder), "/held/" + holder);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (HELD_REQUESTS.size() < DownloadManager.PLATFORM_WORKERS && System.nanoTime() < deadline) Thread.sleep(10);
		assertEquals(DownloadManager.PLATFORM_WORKERS, HELD_REQUESTS.size(), "the held downloads must occupy every worker slot before the pair enqueues: " + HELD_REQUESTS);
		enqueuePlatformDownload(manager, "order-small.bin", smallContent, "/held/one-byte.txt");
		enqueuePlatformDownload(manager, "order-large.bin", largeContent, "/held/multi-chunk.bin");
		HELD_LATCHES.get(holderNames.get(0)).countDown(); // exactly one worker slot frees
		awaitHeld("multi-chunk.bin");
		assertFalse(HELD_REQUESTS.contains("one-byte.txt"), "the small queued file must stay parked; the released slot went to the larger one: " + HELD_REQUESTS);
		HELD_LATCHES.values().forEach(CountDownLatch::countDown);
		deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (HELD_REQUESTS.size() < HELD_LATCHES.size() && System.nanoTime() < deadline) Thread.sleep(10);
		manager.joinAll();
		manager.finish();
		cache.close();
		assertArrayEquals(smallContent, Files.readAllBytes(layout.objectFile(HashUtils.getHash(writeExpected("order-small.bin", smallContent)))));
		assertArrayEquals(largeContent, Files.readAllBytes(layout.objectFile(HashUtils.getHash(writeExpected("order-large.bin", largeContent)))));
	}

	/**
	 * The pinned dispatch property: a platform-blocked head must not stall host-servable files behind it. Every worker
	 * slot parks at its latch, the largest queued file is platform-routed, and a smaller file with no platform sources
	 * enqueues behind it - the host fetch must land while the head still waits. Under the after-the-choice budget gate
	 * this dispatch never happened: the whole pump stopped at the blocked head.
	 */
	@Test
	void aHostServableFileDispatchesWhileThePlatformBlockedHeadWaits() throws Exception {
		layout = new DataRootResolver.Layout(tempDir.resolve("data"));
		PlatformCache cache = PlatformCache.open(tempDir.resolve("platform-cache"));
		byte[] headContent = CONTENTS.get("blocked-head.bin");
		byte[] hostContent = CONTENTS.get("one-byte.txt");
		List<String> holderNames = List.of("chunk-under.bin", "chunk-exact.bin", "chunk-over.bin", "held.bin", "resume.bin");
		long totalBytes = holderNames.size() * (long) CONTENTS.get("held.bin").length + headContent.length + hostContent.length;
		DownloadManager manager = new DownloadManager(totalBytes, layout, cache);
		FakeTransport transport = new FakeTransport(hostContent);
		manager.attachTransport(transport);
		for (String holder : holderNames) HELD_LATCHES.put(holder, new CountDownLatch(1));
		HELD_LATCHES.put("blocked-head.bin", new CountDownLatch(1));
		int arrivalsBefore = HELD_REQUESTS.size(); // earlier tests' arrivals share the static record; only fresh ones count
		for (String holder : holderNames) enqueuePlatformDownload(manager, holder, CONTENTS.get(holder), "/held/" + holder);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (HELD_REQUESTS.size() < arrivalsBefore + DownloadManager.PLATFORM_WORKERS && System.nanoTime() < deadline) Thread.sleep(10);
		assertEquals(arrivalsBefore + DownloadManager.PLATFORM_WORKERS, HELD_REQUESTS.size(), "every worker slot must be parked before the pair enqueues: " + HELD_REQUESTS);
		enqueuePlatformDownload(manager, "blocked-head.bin", headContent, "/held/blocked-head.bin");
		String hostSha1 = HashUtils.getHash(writeExpected("host-served.bin", hostContent));
		Path hostDestination = tempDir.resolve("active").resolve("host-served.bin");
		manager.download(hostDestination, hostSha1, null, "mods", List.of(), hostContent.length, () -> {}, category -> {});
		awaitHostFetch(transport, hostSha1);
		assertFalse(HELD_REQUESTS.contains("blocked-head.bin"), "the platform head must still be parked; the host file cannot have freed a worker: " + HELD_REQUESTS);
		HELD_LATCHES.values().forEach(CountDownLatch::countDown);
		manager.joinAll();
		manager.finish();
		cache.close();
		assertArrayEquals(hostContent, Files.readAllBytes(layout.objectFile(hostSha1)));
	}

	/** Fails fast once the transport records the host fetch: the dispatch the pin demands happened. */
	private static void awaitHostFetch(FakeTransport transport, String sha1) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (transport.fetches.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
		assertEquals(List.of(sha1), transport.fetches, "the host-servable file must dispatch while the platform-blocked head waits");
	}

	/** Queues one file whose only source is a /held path, so the attempt parks at that path's latch. */
	private void enqueuePlatformDownload(DownloadManager manager, String name, byte[] content, String heldPath) throws IOException {
		String sha1 = HashUtils.getHash(writeExpected(name, content));
		Path destination = tempDir.resolve("active").resolve(name);
		List<DownloadSource> sources = List.of(new DownloadSource("http://127.0.0.1:" + server.getAddress().getPort() + heldPath, DownloadSource.Provider.MODRINTH));
		manager.download(destination, sha1, null, "mods", sources, content.length, () -> {}, category -> {});
	}

	/** Fails fast once the named request reaches the server: its dispatch happened, whatever parked at the latch. */
	private static void awaitHeld(String name) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!HELD_REQUESTS.contains(name) && System.nanoTime() < deadline) Thread.sleep(10);
		assertTrue(HELD_REQUESTS.contains(name), name + " must have been dispatched, arrivals were " + HELD_REQUESTS);
	}

	private Path downloadViaPlatform(String name, String contentKey, String serverPath, FakeTransport transport) throws Exception {
		layout = new DataRootResolver.Layout(tempDir.resolve("data"));
		Path destination = tempDir.resolve("active").resolve(name); // the projection path; the store is the real target
		byte[] content = CONTENTS.get(contentKey);
		PlatformCache cache = PlatformCache.open(tempDir.resolve("platform-cache"));
		DownloadManager manager = new DownloadManager(content.length, layout, cache);
		manager.attachTransport(transport);
		List<DownloadSource> sources = List.of(new DownloadSource("http://127.0.0.1:" + server.getAddress().getPort() + serverPath, DownloadSource.Provider.MODRINTH));
		String sha1 = HashUtils.getHash(writeExpected(name, content));
		manager.download(destination, sha1, null, "mods", sources, content.length, () -> {}, category -> {});
		manager.joinAll();
		manager.finish();
		cache.close();
		return layout.objectFile(sha1);
	}

	/** A zero-byte file needs nobody: no platform, no host wire - the client just creates it and promotion judges the hash. */
	@Test
	void emptyFileMaterializesWithoutAnySourceOrWire() throws Exception {
		layout = new DataRootResolver.Layout(tempDir.resolve("data"));
		Path destination = tempDir.resolve("active").resolve("zero.bin");
		PlatformCache cache = PlatformCache.open(tempDir.resolve("platform-cache"));
		DownloadManager manager = new DownloadManager(0, layout, cache);
		manager.attachTransport(new FakeTransport(null)); // any host fetch would fail this test
		String sha1 = HashUtils.getHash(writeExpected("zero.bin", new byte[0]));
		manager.download(destination, sha1, null, "config", List.of(), 0, () -> {}, category -> {});
		manager.joinAll();
		manager.finish();
		cache.close();
		assertArrayEquals(new byte[0], Files.readAllBytes(layout.objectFile(sha1)));
	}

	/** First attempt dies at 80%; the retry must carry the partial's offset on the wire and finish the object from the 206. */
	@Test
	void anInterruptedPlatformDownloadResumesBehindItsPartial() throws Exception {
		byte[] expected = CONTENTS.get("resume.bin");
		RESUME_REQUESTS.clear();
		Path stored = downloadViaPlatform("resumed.bin", "resume.bin", "/resume-lab/resumable", new FakeTransport(null));
		assertArrayEquals(expected, Files.readAllBytes(stored));
		assertEquals(2, RESUME_REQUESTS.size());
		assertNull(RESUME_REQUESTS.get(0).range(), "the first attempt has no partial, so no Range header");
		assertEquals("bytes=" + expected.length * 8 / 10 + "-", RESUME_REQUESTS.get(1).range(), "the retry must resume exactly behind the stored prefix");
		for (ServedRequest served : RESUME_REQUESTS) assertEquals(NetUtils.USER_AGENT, served.userAgent(), "downloads carry the User-Agent too");
	}

	/** A redirecting non-CurseForge source is followed by the pool, and the file behind the hop lands exact. */
	@Test
	void aRedirectingSourceIsFollowedAndTheFileLandsExact() throws Exception {
		byte[] expected = CONTENTS.get("resume.bin");
		Path stored = downloadViaPlatform("redirected.bin", "resume.bin", "/redirect/resume.bin", new FakeTransport(null));
		assertArrayEquals(expected, Files.readAllBytes(stored));
	}

	/** First attempt dies at 40%; the retry sends the Range, the server ignores it with a full 200, and the object still lands exact. */
	@Test
	void aRangeIgnoringServerStillYieldsExactBytesFromZero() throws Exception {
		byte[] expected = CONTENTS.get("resume.bin");
		RESUME_REQUESTS.clear();
		Path stored = downloadViaPlatform("range-ignored.bin", "resume.bin", "/resume-lab/range-ignored", new FakeTransport(null));
		assertArrayEquals(expected, Files.readAllBytes(stored));
		assertEquals(2, RESUME_REQUESTS.size());
		assertEquals("bytes=" + expected.length * 4 / 10 + "-", RESUME_REQUESTS.get(1).range(), "the retry does send the Range header");
	}

	/** A 416 verdict deletes the partial: the host fallback receives a clean, empty temp instead of the stale prefix. */
	@Test
	void aStalePartialIsDeletedAndTheNextAttemptStartsClean() throws Exception {
		byte[] expected = CONTENTS.get("resume.bin");
		RESUME_REQUESTS.clear();
		FakeTransport transport = new FakeTransport(expected);
		Path stored = downloadViaPlatform("stale.bin", "resume.bin", "/resume-lab/stale", transport);
		assertArrayEquals(expected, Files.readAllBytes(stored));
		assertEquals(2, RESUME_REQUESTS.size());
		assertEquals("bytes=" + expected.length * 4 / 10 + "-", RESUME_REQUESTS.get(1).range());
		assertEquals(List.of(0L), transport.resumeOffsets, "the 416 must have deleted the stale partial: the host wire resumes from zero");
	}

	/** The expected bytes on disk, so the store's sha1 promotion judges real content. */
	private Path writeExpected(String name, byte[] content) throws IOException {
		Path expected = tempDir.resolve("expected-" + name);
		Files.createDirectories(expected.getParent());
		Files.write(expected, content);
		return expected;
	}

	/** A transport that only records what it was asked for, optionally serving one file's bytes as the host would. */
	private static final class FakeTransport implements PackTransport {
		private final byte[] servedBytes;
		private final List<String> fetches = new ArrayList<>();
		private final List<Long> resumeOffsets = new ArrayList<>();
		private final AtomicInteger failures = new AtomicInteger();

		FakeTransport(byte[] servedBytes) {
			this.servedBytes = servedBytes;
		}

		@Override
		public CompletableFuture<Path> downloadObject(byte[] key, Path destination, long fileSize, IntConsumer progress) {
			String fetched = new String(key, StandardCharsets.UTF_8);
			fetches.add(fetched);
			if (servedBytes == null) {
				failures.incrementAndGet();
				return CompletableFuture.failedFuture(new IOException("no host wire in this test"));
			}
			try {
				Files.createDirectories(destination);
				long offset = PartialResume.nextByte(destination, fileSize);
				resumeOffsets.add(offset);
				if (offset >= servedBytes.length) return CompletableFuture.completedFuture(destination);
				try (OutputStream out = PartialResume.writer(destination, fileSize, offset)) {
					out.write(servedBytes, (int) offset, servedBytes.length - (int) offset);
				}
				progress.accept(servedBytes.length - (int) offset);
				return CompletableFuture.completedFuture(destination);
			} catch (IOException e) {
				failures.incrementAndGet();
				return CompletableFuture.failedFuture(e);
			}
		}

		@Override
		public CompletableFuture<Path> downloadSmallObject(byte[] key, Path destination, long maxBytes, OutputStream tap) {
			return CompletableFuture.failedFuture(new IOException("small objects are not part of this test"));
		}

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, PackTransport.DocumentConditional conditional, IntConsumer progress) {
			return CompletableFuture.failedFuture(new IOException("documents are not part of this test"));
		}

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, PackTransport.DocumentConditional conditional, OutputStream tap) {
			return CompletableFuture.failedFuture(new IOException("documents are not part of this test"));
		}

		@Override
		public String windowSummary() {
			return "no window";
		}

		@Override
		public void abortTransfers() {}

		@Override
		public void close() {}
	}
}
