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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.sun.net.httpserver.HttpServer;

import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.LocalFileWriter;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;

/**
 * The platform path against a real HTTP server: platform-first priority (the transport never sees a file the platform
 * served), the 404 fallback to the host wire, and a file universe across the chunk edges - the same bytes in, whatever
 * the route.
 */
class DownloadManagerPlatformTest {

	private static HttpServer server;
	private static final Map<String, byte[]> CONTENTS = new HashMap<>();
	private static final List<String> transportFetches = new CopyOnWriteArrayList<>();

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
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
		server.start();
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
		// 12 MiB over 4 MiB chunks: the first segment plus two idle-window takes.
		assertEquals(3, transport.fetches.size());
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

		FakeTransport(byte[] servedBytes) {
			this.servedBytes = servedBytes;
		}

		@Override
		public CompletableFuture<Path> downloadFile(byte[] key, Path destination, long offset, long endInclusive, IntConsumer progress, int lane) {
			fetches.add(new String(key, StandardCharsets.UTF_8));
			if (servedBytes == null) return CompletableFuture.failedFuture(new IOException("no host wire in this test"));
			try {
				int end = endInclusive < 0 ? servedBytes.length - 1 : (int) endInclusive;
				byte[] suffix = new byte[end + 1 - (int) offset];
				System.arraycopy(servedBytes, (int) offset, suffix, 0, suffix.length);
				if (offset > 0) {
					try (OutputStream out = LocalFileWriter.openAt(destination, offset)) {
						out.write(suffix);
					}
				} else {
					Files.write(destination, suffix);
				}
				progress.accept(suffix.length);
				return CompletableFuture.completedFuture(destination);
			} catch (IOException e) {
				return CompletableFuture.failedFuture(e);
			}
		}

		@Override
		public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer progress) {
			return CompletableFuture.failedFuture(new IOException("documents are not part of this test"));
		}

		@Override
		public void abortTransfers() {}

		@Override
		public void close() {}
	}
}
