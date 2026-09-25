package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import pl.skidam.automodpack_core.platforms.CurseForgeAPI;
import pl.skidam.automodpack_core.platforms.CurseForgeAPI.TrustedEndpoint;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;

/**
 * The batched dead-link refetch asks both platforms in parallel and merges: one API failing outright must leave the
 * other's sources intact. Both lookups are pointed at a local server through the API url seams, so the isolation is
 * proven against real calls.
 */
class FetchManagerRefetchTest {

	private static HttpServer server;
	private static final List<String> VERSION_FILE_REQUESTS = new ArrayList<>();
	private static final List<String> FINGERPRINT_REQUESTS = new ArrayList<>();
	private static final AtomicInteger VERSION_FILE_FAILURES = new AtomicInteger();
	private static final AtomicInteger FINGERPRINT_FAILURES = new AtomicInteger();

	private static final String SHA1 = "a".repeat(40);
	private static final String MURMUR = "12345";
	private static final String VERSION_FILES_200 = """
			{"%s":{"project_id":"aaaa1111","version_number":"1.2.3","version_type":"release","status":"listed","files":[{"url":"http://cdn.example/a.jar","filename":"a.jar","size":123,"hashes":{"sha1":"%s"}}]}}"""
			.formatted(SHA1, SHA1);
	private static final String PROJECTS_200 = """
			[{"id":"aaaa1111","slug":"a-mod","status":"approved"}]""";
	private static final String FINGERPRINTS_200 = """
			{"data":{"exactMatches":[{"file":{"releaseType":1,"hashes":[{"algo":1,"value":"%s"}],"downloadUrl":"http://cdn.example/a.jar","fileName":"a.jar","displayName":"A 1.2.3","fileLength":123,"modId":7}}]}}"""
			.formatted(SHA1);
	private static final String MODS_200 = """
			{"data":[{"id":7,"isAvailable":true,"slug":"a-mod","links":{"websiteUrl":"https://www.curseforge.com/minecraft/mc-mods/a-mod"}}]}""";

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/modrinth/version_files", exchange -> serve(exchange, VERSION_FILE_FAILURES, VERSION_FILE_REQUESTS, VERSION_FILES_200));
		server.createContext("/modrinth/projects", exchange -> serve(exchange, new AtomicInteger(), new ArrayList<>(), PROJECTS_200));
		server.createContext("/v1/fingerprints", exchange -> serve(exchange, FINGERPRINT_FAILURES, FINGERPRINT_REQUESTS, FINGERPRINTS_200));
		server.createContext("/v1/mods", exchange -> serve(exchange, new AtomicInteger(), new ArrayList<>(), MODS_200));
		server.start();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	/** Records every arriving request; while failures remain, requests get a 500, after that the 200 body. */
	private static void serve(HttpExchange exchange, AtomicInteger failures, List<String> requests, String body) throws IOException {
		boolean failing;
		synchronized (requests) {
			requests.add(exchange.getRequestURI().getPath());
			failing = failures.getAndDecrement() > 0;
		}
		if (failing) {
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
			return;
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
		exchange.close();
	}

	private static String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static FetchManager.Lookups lookupsPointedAtTheFixture() {
		return new FetchManager.Lookups(list -> ModrinthAPI.getModsInfosFromListOfSHA1(base() + "/modrinth/version_files", list),
				ids -> ModrinthAPI.getProjectSlugs(base() + "/modrinth/projects", ids),
				hashes -> CurseForgeAPI.getModInfosFromFingerPrints(base() + "/v1", new TrustedEndpoint("http", "127.0.0.1", server.getAddress().getPort()), hashes));
	}

	@Test
	void aDeadModrinthRefetchLeavesTheCurseForgeSourcesIntact(@TempDir Path temporaryDirectory) throws Exception {
		VERSION_FILE_REQUESTS.clear();
		FINGERPRINT_REQUESTS.clear();
		VERSION_FILE_FAILURES.set(2); // both the call and its one retry fail: the leg is dead
		FINGERPRINT_FAILURES.set(0);
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory.resolve("cache"))) {
			FetchManager manager = new FetchManager(List.of(new FetchManager.FetchData("mods/a.jar", SHA1, MURMUR, "mods")), cache, lookupsPointedAtTheFixture());
			assertTrue(manager.markDeadPlatformLink(SHA1, MURMUR, "mods"));
			List<DownloadSource> sources = manager.awaitMetadataRefetch(SHA1);
			assertEquals(1, sources.size(), "the dead Modrinth endpoint must not take the CurseForge source down with it");
			assertEquals(DownloadSource.Provider.CURSEFORGE, sources.get(0).provider());
			assertEquals("http://cdn.example/a.jar", sources.get(0).url());
			assertEquals(2, VERSION_FILE_REQUESTS.size(), "the failing Modrinth call must have retried exactly once");
			assertEquals(1, FINGERPRINT_REQUESTS.size(), "the healthy CurseForge call must not retry");
		}
	}

	@Test
	void aDeadCurseForgeRefetchLeavesTheModrinthSourcesIntact(@TempDir Path temporaryDirectory) throws Exception {
		VERSION_FILE_REQUESTS.clear();
		FINGERPRINT_REQUESTS.clear();
		VERSION_FILE_FAILURES.set(0);
		FINGERPRINT_FAILURES.set(2); // both the call and its one retry fail: the leg is dead
		try (PlatformCache cache = PlatformCache.open(temporaryDirectory.resolve("cache"))) {
			FetchManager manager = new FetchManager(List.of(new FetchManager.FetchData("mods/a.jar", SHA1, MURMUR, "mods")), cache, lookupsPointedAtTheFixture());
			assertTrue(manager.markDeadPlatformLink(SHA1, MURMUR, "mods"));
			List<DownloadSource> sources = manager.awaitMetadataRefetch(SHA1);
			assertEquals(1, sources.size(), "the dead CurseForge endpoint must not take the Modrinth source down with it");
			assertEquals(DownloadSource.Provider.MODRINTH, sources.get(0).provider());
			assertEquals("http://cdn.example/a.jar", sources.get(0).url());
			assertEquals(2, FINGERPRINT_REQUESTS.size(), "the failing CurseForge call must have retried exactly once");
		}
	}
}
