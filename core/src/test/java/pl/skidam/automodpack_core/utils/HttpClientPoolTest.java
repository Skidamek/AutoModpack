package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * The pooled API client's retry discipline against a real local server, exercised through its JSON callers: exactly one
 * retry on 429/5xx, never on other 4xx, and the User-Agent on every request the server receives.
 */
class HttpClientPoolTest {

	/** One request the local server answered. */
	private record Served(String method, String path, String userAgent) {}
	private record ScriptedResponse(int status, String retryAfter) {}

	private static HttpServer server;
	private static final Map<String, Deque<ScriptedResponse>> SCRIPTED = new HashMap<>();
	private static final Map<String, String> BODIES = new HashMap<>();
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
		BODIES.putIfAbsent("/scripted/not-found", "[1,2]");
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/scripted", exchange -> {
			String path = exchange.getRequestURI().getPath();
			ScriptedResponse scripted;
			synchronized (SCRIPTED) {
				SERVED.add(new Served(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("User-Agent")));
				Deque<ScriptedResponse> script = SCRIPTED.get(path);
				scripted = script == null || script.isEmpty() ? new ScriptedResponse(500, null) : script.poll();
			}
			if (scripted.retryAfter() != null) exchange.getResponseHeaders().set("Retry-After", scripted.retryAfter());
			byte[] body = BODIES.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8);
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
		BODIES.put(path, body);
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
}
