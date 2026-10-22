package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.WireCodec;

/**
 * The pooled API client's retry discipline against a real local server, exercised through its JSON callers: exactly one
 * retry on 429/5xx, never on other 4xx, and the User-Agent on every request the server receives.
 */
class HttpClientPoolTest {

	/** One request the local server answered. */
	private record Served(String method, String path, String userAgent, String acceptEncoding) {}
	private record ScriptedResponse(int status, String retryAfter) {}

	private static HttpServer server;
	private static final Map<String, Deque<ScriptedResponse>> SCRIPTED = new HashMap<>();
	private static final Map<String, byte[]> BODIES = new HashMap<>();
	private static final Map<String, WireCodec> ENCODINGS = new HashMap<>();
	private static final List<Served> SERVED = new ArrayList<>();

	@BeforeAll
	static void startServer() throws IOException {
		script("/scripted/retry-then-success", 500, null, "[1,2]");
		script("/scripted/retry-then-success", 200, null, "[1,2]");
		script("/scripted/always-500", 500, null, "[1,2]");
		script("/scripted/always-500", 500, null, "[1,2]");
		script("/scripted/rate-limited", 429, "0", "[3]");
		script("/scripted/rate-limited", 200, null, "[3]");
		script("/scripted/not-found", 404, null, "[1,2]");
		script("/scripted/not-found", 200, null, "[1,2]");
		script("/scripted/hashes", 500, null, "{\"a\":{}}");
		script("/scripted/hashes", 200, null, "{\"a\":{}}");
		BODIES.putIfAbsent("/scripted/not-found", "[1,2]".getBytes(StandardCharsets.UTF_8));
		scriptEncoded("/scripted/encoded-gzip", WireCodec.GZIP, "[4]");
		scriptEncoded("/scripted/encoded-zstd", WireCodec.ZSTD, "[5]");
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/scripted", exchange -> {
			String path = exchange.getRequestURI().getPath();
			ScriptedResponse scripted;
			synchronized (SCRIPTED) {
				SERVED.add(new Served(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("User-Agent"), exchange.getRequestHeaders().getFirst("Accept-Encoding")));
				Deque<ScriptedResponse> script = SCRIPTED.get(path);
				scripted = script == null || script.isEmpty() ? new ScriptedResponse(500, null) : script.poll();
			}
			if (scripted.retryAfter() != null) exchange.getResponseHeaders().set("Retry-After", scripted.retryAfter());
			WireCodec encoding = ENCODINGS.get(path);
			if (encoding != null) exchange.getResponseHeaders().set("Content-Encoding", encoding.wireName());
			byte[] body = BODIES.getOrDefault(path, new byte[0]);
			exchange.sendResponseHeaders(scripted.status(), body.length == 0 ? -1 : body.length);
			if (body.length > 0) try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	private static void script(String path, int status, String retryAfter, String body) {
		SCRIPTED.computeIfAbsent(path, ignored -> new ArrayDeque<>()).add(new ScriptedResponse(status, retryAfter));
		BODIES.put(path, body.getBytes(StandardCharsets.UTF_8));
	}

	private static void scriptEncoded(String path, WireCodec codec, String body) {
		script(path, 200, null, body);
		ByteArrayOutputStream encoded = new ByteArrayOutputStream();
		try (OutputStream out = codec.wrap(encoded)) {
			out.write(body.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new IllegalStateException("Cannot encode the test body", e);
		}
		BODIES.put(path, encoded.toByteArray());
		ENCODINGS.put(path, codec);
	}

	private static String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	/** Fail fast on whatever the local server received: the exact request count and the User-Agent on every one of them. */
	private static void assertServed(String path, String method, int count) {
		List<Served> hits = SERVED.stream().filter(served -> served.path().equals(path)).toList();
		assertEquals(count, hits.size(), "unexpected request count at " + path);
		for (Served served : hits) {
			assertEquals(method, served.method());
			assertEquals(NetUtils.USER_AGENT, served.userAgent(), "every outbound API request must carry the User-Agent");
			assertTrue(served.acceptEncoding() != null && served.acceptEncoding().contains("zstd") && served.acceptEncoding().contains("gzip"), "every outbound API request must offer both content codings");
		}
	}

	@Test
	void fiveHundredThenTwoHundredRetriesOnceAndSucceeds() {
		SERVED.clear();
		String path = "/scripted/retry-then-success";
		JsonArray array = Json.fromUrlAsArray(url(path));
		assertEquals(2, array.size());
		assertServed(path, "GET", 2);
	}

	@Test
	void fiveHundredTwiceReturnsNullAfterTheOneRetry() {
		SERVED.clear();
		String path = "/scripted/always-500";
		assertNull(Json.fromUrlAsArray(url(path)));
		assertServed(path, "GET", 2);
	}

	@Test
	void rateLimitedWithZeroRetryAfterRetriesImmediately() {
		SERVED.clear();
		String path = "/scripted/rate-limited";
		JsonArray array = Json.fromUrlAsArray(url(path));
		assertEquals(1, array.size());
		assertServed(path, "GET", 2);
	}

	@Test
	void notFoundIsAVerdictThatNeverRetries() {
		SERVED.clear();
		String path = "/scripted/not-found";
		assertNull(Json.fromUrlAsArray(url(path)));
		assertServed(path, "GET", 1);
	}

	@Test
	void gzipAndZstdBodiesAreDecodedByTheSinglePool() {
		SERVED.clear();
		String gzip = "/scripted/encoded-gzip";
		String zstd = "/scripted/encoded-zstd";
		assertEquals(1, Json.fromUrlAsArray(url(gzip)).size());
		assertEquals(1, Json.fromUrlAsArray(url(zstd)).size());
		assertServed(gzip, "GET", 1);
		assertServed(zstd, "GET", 1);
	}

	@Test
	void theHashPostRetriesOnFiveHundredAndParsesTheAnswer() {
		SERVED.clear();
		String path = "/scripted/hashes";
		JsonObject object = Json.fromModrinthUrl(url(path), List.of("a".repeat(40)));
		assertEquals(Map.of(), object.getAsJsonObject("a").asMap());
		assertServed(path, "POST", 2);
	}

	@Test
	void anEmptyHashListNeverTouchesTheWire() {
		SERVED.clear();
		assertNull(Json.fromModrinthUrl(url("/scripted/hashes"), List.of()));
		assertTrue(SERVED.isEmpty(), "a null hash list must not send a request");
	}

	@Test
	void aBodyPastTheTripwireIsRejected() {
		SERVED.clear();
		String path = "/scripted/huge";
		SCRIPTED.computeIfAbsent(path, ignored -> new ArrayDeque<>()).add(new ScriptedResponse(200, null));
		BODIES.put(path, new byte[HttpClientPool.MAX_BODY_BYTES + 1]);
		IOException thrown = assertThrows(IOException.class, () -> HttpClientPool.request(url(path), Map.of(), null, true));
		assertTrue(thrown.getMessage().contains("exceeded"));
		assertServed(path, "GET", 1);
	}

	@Test
	void aDecodedBodyPastTheTripwireIsRejected() throws IOException {
		SERVED.clear();
		String path = "/scripted/bomb";
		SCRIPTED.computeIfAbsent(path, ignored -> new ArrayDeque<>()).add(new ScriptedResponse(200, null));
		ByteArrayOutputStream encoded = new ByteArrayOutputStream();
		try (OutputStream out = WireCodec.GZIP.wrap(encoded)) {
			out.write(new byte[HttpClientPool.MAX_BODY_BYTES + 1]);
		}
		BODIES.put(path, encoded.toByteArray());
		ENCODINGS.put(path, WireCodec.GZIP);
		IOException thrown = assertThrows(IOException.class, () -> HttpClientPool.request(url(path), Map.of(), null, true));
		assertTrue(thrown.getMessage().contains("exceeded"));
		assertServed(path, "GET", 1);
	}
}
