package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import pl.skidam.automodpack_core.platforms.PlatformCache;
import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.HostTiles;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The rescue reaching the host wire on its own, and staying out of its way when it should.
 *
 * <p>
 * The crawl run serves a platform far below the adequacy bar and gives the host a fast in-memory transport, so the only
 * way the host sees traffic is if the probe measures it and the verdict engages. The healthy run serves a platform above
 * the bar and asserts the host is never touched at all - the author's channel carries the whole run.
 */
class HostProbeRoutingTest {

	@TempDir
	Path tempDir;

	private static final byte[] CRAWLING = new byte[8 * 1024 * 1024];
	private static final long MiB = 1024L * 1024L;

	private HttpServer server;
	private PlatformCache cache;
	private DataRootResolver.Layout layout;
	private RecordingTransport host;
	private final List<String> platformServed = Collections.synchronizedList(new ArrayList<>());

	/** Serves {@code speedBytesPerSecond} on one connection at a time, so the measured rate is the throttle and not the loopback. */
	private HttpServer serve(double speedBytesPerSecond) throws IOException {
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		http.setExecutor(Executors.newCachedThreadPool(r -> {
			Thread thread = new Thread(r, "probe-test-http");
			thread.setDaemon(true);
			return thread;
		}));
		http.createContext("/mod", exchange -> {
			platformServed.add(exchange.getRequestURI().getPath());
			try {
				exchange.sendResponseHeaders(200, CRAWLING.length);
				long perChunk = Math.max(1, (long) (speedBytesPerSecond / 20));
				// A real rate limit, not a small chunk: loopback would otherwise deliver the whole body at wire speed and the
				// measured platform rate would be whatever the loopback manages, which says nothing about the crawl being tested.
				long pauseNanos = Math.round(perChunk / speedBytesPerSecond * 1_000_000_000L);
				try (OutputStream out = exchange.getResponseBody()) {
					for (int written = 0; written < CRAWLING.length; written += perChunk) {
						out.write(CRAWLING, written, (int) Math.min(perChunk, CRAWLING.length - written));
						out.flush();
						if (pauseNanos > 0) {
							try {
								Thread.sleep(pauseNanos / 1_000_000L, (int) (pauseNanos % 1_000_000L));
							} catch (InterruptedException interrupted) {
								Thread.currentThread().interrupt();
								break;
							}
						}
					}
				}
			} catch (IOException ignored) {
				// the client cancelled, which is the rescue doing its job
			}
			exchange.close();
		});
		http.start();
		return http;
	}

	private void setUp(double platformBytesPerSecond) throws IOException {
		layout = new DataRootResolver.Layout(tempDir.resolve("data"));
		cache = PlatformCache.open(tempDir.resolve("platform-cache"));
		host = new RecordingTransport();
		server = serve(platformBytesPerSecond);
	}

	private void tearDown() {
		if (server != null) server.stop(0);
		if (cache != null) cache.close();
	}

	/** Waits for every queued file to settle, or fails with what the run did instead. */
	private void awaitRun(DownloadManager manager, String what) throws Exception {
		CompletableFuture<Void> settled = CompletableFuture.runAsync(() -> {
			try {
				manager.joinAll();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			settled.get(30, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			fail("the run never settled (" + what + "); host fetches=" + host.fetches + " platform served=" + platformServed.size());
		}
		manager.finish();
	}

	private boolean stored() {
		return Files.exists(layout.objectFile(HashUtils.sha1(CRAWLING)));
	}

	@Test
	void aCrawlingPlatformReachesTheHostWireWithoutBeingToldTo() throws Exception {
		setUp(64 * 1024);
		try {
			// One file is enough now: the probe takes from a file the platform is already sending.
			int files = 1;
			DownloadManager manager = new DownloadManager((long) files * CRAWLING.length, layout, cache);
			manager.attachTransport(host);
			List<DownloadSource> sources = List.of(new DownloadSource("http://127.0.0.1:" + server.getAddress().getPort() + "/mod/a", DownloadSource.Provider.MODRINTH));
			for (int i = 0; i < files; i++) {
				Path destination = tempDir.resolve("active").resolve("mod-" + i + ".jar");
				manager.download(destination, HashUtils.sha1(CRAWLING), null, "mods", sources, CRAWLING.length, () -> {}, category -> {});
			}
			awaitRun(manager, "crawl");
			assertFalse(host.fetches.isEmpty(), "a platform crawling at 64 KiB/s must hand the run to the host wire, which only the probe can reach: " + host.fetches);
			assertTrue(stored(), "the run still finished");
			assertTrue(host.tiles.get() > 0, "the host's first fact came from a tile of a file the platform was already sending, not from a second source for it");
		} finally {
			tearDown();
		}
	}

	@Test
	void aPlatformAlreadyDeliveringKeepsEveryByteOnTheAuthorChannel() throws Exception {
		setUp(64 * MiB);
		try {
			DownloadManager manager = new DownloadManager(CRAWLING.length, layout, cache);
			manager.attachTransport(host);
			List<DownloadSource> sources = List.of(new DownloadSource("http://127.0.0.1:" + server.getAddress().getPort() + "/mod/b", DownloadSource.Provider.MODRINTH));
			Path destination = tempDir.resolve("active").resolve("mod.jar");
			manager.download(destination, HashUtils.sha1(CRAWLING), null, "mods", sources, CRAWLING.length, () -> {}, category -> {});
			awaitRun(manager, "healthy");
			assertTrue(stored(), "the platform served the file");
			assertTrue(host.fetches.isEmpty(), "a platform above the bar owes the author the whole file, so the host must not be probed at all: " + host.fetches);
		} finally {
			tearDown();
		}
	}

	/** Serves objects out of memory from the prefix the probe leaves behind, so a resumed file never starts over. */
	private static final class RecordingTransport implements PackTransport, HostTiles {

		private final List<String> fetches = Collections.synchronizedList(new ArrayList<>());
		private final AtomicInteger tiles = new AtomicInteger();

		@Override
		public CompletableFuture<Path> downloadObject(byte[] key, Path destination, long fileSize, IntConsumer progress) {
			fetches.add(new String(key, StandardCharsets.UTF_8));
			try {
				Files.createDirectories(destination);
				long offset = PartialResume.nextByte(destination, fileSize);
				long total = Math.min(fileSize, CRAWLING.length);
				if (offset >= total) return CompletableFuture.completedFuture(destination);
				try (OutputStream out = PartialResume.writer(destination, fileSize, offset)) {
					out.write(CRAWLING, (int) offset, (int) (total - offset));
				}
				progress.accept((int) (total - offset));
				return CompletableFuture.completedFuture(destination);
			} catch (IOException e) {
				return CompletableFuture.failedFuture(e);
			}
		}

		@Override
		public CompletableFuture<Path> downloadTile(byte[] sha1Hex, Path sliceDirectory, long fileSize, long tileStart, IntConsumer progress) {
			fetches.add(new String(sha1Hex, StandardCharsets.UTF_8));
			tiles.incrementAndGet();
			long length = PartialResume.sliceLength(tileStart, fileSize);
			try {
				Files.createDirectories(sliceDirectory);
				long total = Math.min(fileSize, CRAWLING.length);
				if (tileStart >= total) return CompletableFuture.completedFuture(sliceDirectory);
				try (OutputStream out = Files.newOutputStream(PartialResume.sliceHostTemp(sliceDirectory, tileStart))) {
					out.write(CRAWLING, (int) tileStart, (int) Math.min(length, total - tileStart));
				}
				progress.accept((int) Math.min(length, total - tileStart));
				PartialResume.promoteSlice(sliceDirectory, tileStart, fileSize);
				return CompletableFuture.completedFuture(sliceDirectory);
			} catch (IOException e) {
				return CompletableFuture.failedFuture(e);
			}
		}

		@Override
		public CompletableFuture<Path> downloadSmallObject(byte[] sha1Hex, Path destination, long maxBytes, OutputStream tap) {
			return downloadObject(sha1Hex, destination, maxBytes, ignored -> {});
		}

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, DocumentConditional conditional, IntConsumer chunkCallback) {
			throw new UnsupportedOperationException();
		}

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, DocumentConditional conditional, OutputStream tap) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String windowSummary() {
			return "recording";
		}

		@Override
		public void abortTransfers() {}

		@Override
		public void close() {}
	}
}
