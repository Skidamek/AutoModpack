package pl.skidam.automodpack_core.platforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import pl.skidam.automodpack_core.platforms.CurseForgeAPI.TrustedEndpoint;
import pl.skidam.automodpack_core.protocol.NetUtils;

/**
 * The CurseForge API client against a local server: the fingerprint + project-page pair parses end to end, the API key is
 * pinned to its endpoint and never follows a redirect, and a 401 is logged once without a retry.
 */
class CurseForgeAPIHttpTest {

	private record Served(String path, String userAgent, String apiKey) {}

	private static HttpServer server;
	private static final List<Served> SERVED = new ArrayList<>();
	private static final Map<String, Deque<Integer>> SCRIPTED = new HashMap<>();

	private static final String SHA1 = "a".repeat(40);
	private static final String FINGERPRINTS_200 = """
			{"data":{"exactMatches":[{"file":{"releaseType":1,"hashes":[{"algo":1,"value":"%s"}],"downloadUrl":"http://cdn.example/a.jar","fileName":"a.jar","displayName":"A 1.2.3","fileLength":123,"modId":7}}]}}"""
			.formatted(SHA1);
	private static final String MODS_200 = """
			{"data":[{"id":7,"isAvailable":true,"slug":"a-mod","links":{"websiteUrl":"https://www.curseforge.com/minecraft/mc-mods/a-mod"}}]}""";

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1", exchange -> {
			String path = exchange.getRequestURI().getPath();
			synchronized (SERVED) {
				SERVED.add(new Served(path, exchange.getRequestHeaders().getFirst("User-Agent"), exchange.getRequestHeaders().getFirst("x-api-key")));
			}
			Integer scripted;
			Deque<Integer> script = SCRIPTED.get(path);
			synchronized (SCRIPTED) {
				scripted = script == null || script.isEmpty() ? 200 : script.poll();
			}
			if (scripted == 302) {
				exchange.getResponseHeaders().set("Location", "http://not-the-endpoint.example/steal");
				exchange.sendResponseHeaders(302, -1);
			} else {
				String body = scripted == 200 ? body(path) : "";
				exchange.sendResponseHeaders(scripted, body.isEmpty() ? -1 : body.length());
				if (!body.isEmpty()) try (OutputStream out = exchange.getResponseBody()) {
					out.write(body.getBytes(StandardCharsets.UTF_8));
				}
			}
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	private static void script(String path, int... statuses) {
		Deque<Integer> script = new ConcurrentLinkedDeque<>();
		for (int status : statuses) script.add(status);
		SCRIPTED.put(path, script);
	}

	private static String body(String path) {
		return path.endsWith("/mods") ? MODS_200 : FINGERPRINTS_200;
	}

	private static String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
	}

	private static TrustedEndpoint localEndpoint() {
		return new TrustedEndpoint("http", "127.0.0.1", server.getAddress().getPort());
	}

	private static Map<String, String> hashes() {
		return Map.of(SHA1, "12345");
	}

	private static List<Served> served(String path) {
		synchronized (SERVED) {
			return SERVED.stream().filter(hit -> hit.path().equals(path)).toList();
		}
	}

	@Test
	void fingerprintsAndProjectPagesParseEndToEnd() {
		SERVED.clear();
		List<CurseForgeAPI> infos = CurseForgeAPI.getModInfosFromFingerPrints(baseUrl(), localEndpoint(), hashes());
		assertEquals(1, infos.size());
		CurseForgeAPI info = infos.get(0);
		assertEquals("http://cdn.example/a.jar", info.downloadUrl());
		assertEquals("a.jar", info.fileName());
		assertEquals("A 1.2.3", info.fileVersion());
		assertEquals("123", info.fileSize());
		assertEquals("release", info.releaseType());
		assertEquals("12345", info.murmurHash());
		assertEquals(SHA1, info.sha1Hash());
		assertEquals(7, info.modId());
		assertEquals("https://www.curseforge.com/minecraft/mc-mods/a-mod", info.projectPageUrl());
		List<Served> fingerprints = served("/v1/fingerprints");
		assertEquals(1, fingerprints.size());
		assertEquals(NetUtils.USER_AGENT, fingerprints.get(0).userAgent());
		assertEquals(CurseForgeAPI.summonKey(), fingerprints.get(0).apiKey());
		assertEquals(1, served("/v1/mods").size());
	}

	@Test
	void theKeyNeverLeavesItsPinnedEndpoint() {
		SERVED.clear();
		List<CurseForgeAPI> infos = CurseForgeAPI.getModInfosFromFingerPrints(baseUrl(), TrustedEndpoint.PRODUCTION, hashes());
		assertTrue(infos.isEmpty(), "a refused lookup is an empty result");
		assertTrue(SERVED.isEmpty(), "the pinned-endpoint refusal must happen before anything is sent");
	}

	@Test
	void theClientNeverFollowsARedirect() {
		SERVED.clear();
		script("/v1/fingerprints", 302);
		List<CurseForgeAPI> infos = CurseForgeAPI.getModInfosFromFingerPrints(baseUrl(), localEndpoint(), hashes());
		assertTrue(infos.isEmpty(), "a redirect answer is not a lookup result");
		assertEquals(1, served("/v1/fingerprints").size(), "the redirect must not be followed");
	}

	@Test
	void anUnauthorizedKeyIsLoggedOnceWithoutARetry() {
		SERVED.clear();
		script("/v1/fingerprints", 401, 200);
		List<CurseForgeAPI> infos = CurseForgeAPI.getModInfosFromFingerPrints(baseUrl(), localEndpoint(), hashes());
		assertTrue(infos.isEmpty());
		assertEquals(1, served("/v1/fingerprints").size(), "a 401 verdict must not retry");
	}
}
