package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class FetchCacheTest {

	private HttpServer server;
	private final AtomicInteger requests = new AtomicInteger();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/body", exchange -> {
			requests.incrementAndGet();
			byte[] body = "fresh".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	@Test
	void aSuccessfulFetchIsCachedOnDisk(@TempDir Path directory) throws Exception {
		FetchCache cache = new FetchCache(directory);
		String answer = cache.get(List.of(url("/body")), body -> new String(body, StandardCharsets.UTF_8), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);
		assertEquals("fresh", answer);
		assertEquals(1, requests.get());
		assertEquals("fresh", cache.get(List.of(url("/body")), body -> new String(body, StandardCharsets.UTF_8), Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));
		assertEquals(1, requests.get(), "a fresh disk copy must not be refetched");
		assertArrayEquals("fresh".getBytes(StandardCharsets.UTF_8), cache.stored(url("/body")));
	}
}
