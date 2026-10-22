package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class HttpClientPoolTest {

	private static HttpServer server;
	private static final AtomicBoolean trickling = new AtomicBoolean(true);

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ok", exchange -> {
			byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.createContext("/huge", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				byte[] chunk = new byte[64 * 1024];
				for (int i = 0; i < (HttpClientPool.MAX_BODY_BYTES / chunk.length) + 4; i++) out.write(chunk);
			} catch (IOException closed) {
			}
		});
		server.createContext("/trickle", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write("ok:".getBytes(StandardCharsets.UTF_8));
				while (trickling.get()) {
					out.write('o');
					out.flush();
					Thread.sleep(200);
				}
			} catch (IOException | InterruptedException closed) {
			}
		});
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
	}

	@AfterAll
	static void stopServer() {
		trickling.set(false);
		server.stop(0);
	}

	private String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	@Test
	void aNormalBodyIsReturned() throws Exception {
		assertEquals("ok", new String(HttpClientPool.request(url("/ok"), Map.of(), null, true).body(), StandardCharsets.UTF_8));
	}

	@Test
	void aBodyPastTheCapIsRefused() {
		IOException thrown = assertThrows(IOException.class, () -> HttpClientPool.request(url("/huge"), Map.of(), null, true));
		assertTrue(thrown.getMessage().contains("exceeded"), thrown.getMessage());
	}

	@Test
	void aTricklingBodyHitsTheExchangeDeadline() {
		IOException thrown = assertTimeoutPreemptively(Duration.ofSeconds(45),
				() -> assertThrows(IOException.class, () -> HttpClientPool.request(url("/trickle"), Map.of(), null, true)));
		assertTrue(thrown.getMessage().contains("did not finish") || thrown.getMessage().contains("exceeded"), thrown.getMessage());
	}
}
