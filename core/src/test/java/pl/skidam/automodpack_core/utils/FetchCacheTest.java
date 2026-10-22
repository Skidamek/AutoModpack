package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class FetchCacheTest {

	private static final Function<byte[], String> TEXT = body -> {
		String text = new String(body, StandardCharsets.UTF_8);
		return text.startsWith("ok:") ? text : null;
	};

	private static final Duration WAIT = Duration.ofSeconds(10);

	private HttpServer server;
	private final AtomicInteger requests = new AtomicInteger();
	private final AtomicReference<String> answer = new AtomicReference<>("ok:first");
	private final AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(0));

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/feed", exchange -> {
			requests.incrementAndGet();
			try {
				gate.get().await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			String text = answer.get();
			byte[] body = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(text == null ? 404 : 200, body.length == 0 ? -1 : body.length);
			if (body.length > 0) try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private List<String> urls() {
		return List.of("http://127.0.0.1:" + server.getAddress().getPort() + "/feed");
	}

	@Test
	void aFreshCopyIsServedFromDiskAndAStaleOneIsRefetched(@TempDir Path directory) throws Exception {
		FetchCache cache = new FetchCache(directory);
		assertEquals("ok:first", cache.get(urls(), TEXT, WAIT).get(10, TimeUnit.SECONDS));
		answer.set("ok:second");
		assertEquals("ok:first", cache.get(urls(), TEXT, WAIT).get(10, TimeUnit.SECONDS));
		assertEquals(1, requests.get());

		age(directory);
		assertEquals("ok:second", cache.get(urls(), TEXT, WAIT).get(10, TimeUnit.SECONDS));
		assertEquals(2, requests.get());
	}

	@Test
	void aFailedOrUnusableRefetchKeepsTheOldCopy(@TempDir Path directory) throws Exception {
		FetchCache cache = new FetchCache(directory);
		assertEquals("ok:first", cache.get(urls(), TEXT, WAIT).get(10, TimeUnit.SECONDS));
		age(directory);
		answer.set("garbage");
		assertEquals("ok:first", cache.get(urls(), TEXT, WAIT).get(10, TimeUnit.SECONDS));
		answer.set(null);
		assertEquals("ok:first", cache.get(urls(), TEXT, WAIT).get(10, TimeUnit.SECONDS));
		assertNull(new FetchCache(null).get(urls(), TEXT, WAIT).get(10, TimeUnit.SECONDS), "with no copy at all a failure is null");
	}

	@Test
	void aRefetchStillRunningAfterTheWaitYieldsTheOldCopy(@TempDir Path directory) throws Exception {
		FetchCache cache = new FetchCache(directory);
		assertEquals("ok:first", cache.get(urls(), TEXT, WAIT).get(10, TimeUnit.SECONDS));
		age(directory);
		gate.set(new CountDownLatch(1));
		answer.set("ok:second");
		assertEquals("ok:first", cache.get(urls(), TEXT, Duration.ofMillis(200)).get(10, TimeUnit.SECONDS));
		assertNull(new FetchCache(null).get(urls(), TEXT, Duration.ofMillis(200)).get(10, TimeUnit.SECONDS));
		gate.get().countDown();
		assertEquals("ok:second", cache.get(urls(), TEXT, WAIT).get(10, TimeUnit.SECONDS), "the late refetch still lands");
	}

	@Test
	void concurrentRequestsForOneUrlShareOneFetch() throws Exception {
		FetchCache cache = new FetchCache(null);
		gate.set(new CountDownLatch(1));
		CompletableFuture<String> first = cache.get(urls(), TEXT, WAIT);
		CompletableFuture<String> second = cache.get(urls(), TEXT, WAIT);
		gate.get().countDown();
		assertEquals("ok:first", first.get(10, TimeUnit.SECONDS));
		assertEquals("ok:first", second.get(10, TimeUnit.SECONDS));
		assertEquals(1, requests.get());
	}

	private static void age(Path directory) throws IOException {
		try (var files = Files.list(directory)) {
			for (Path file : files.toList()) Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(FetchCache.MAX_AGE).minusSeconds(60)));
		}
	}
}
