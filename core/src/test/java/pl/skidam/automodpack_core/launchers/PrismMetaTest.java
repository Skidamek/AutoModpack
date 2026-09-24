package pl.skidam.automodpack_core.launchers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import pl.skidam.automodpack_core.protocol.NetUtils;

/** The launcher meta lookup against a local server: a 200 resolves, a 404 is a plain miss, and the User-Agent rides along. */
class PrismMetaTest {

	private static HttpServer server;
	private static final List<String> USER_AGENTS = new ArrayList<>();
	private static final List<String> PATHS = new ArrayList<>();

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1", exchange -> {
			synchronized (PATHS) {
				USER_AGENTS.add(exchange.getRequestHeaders().getFirst("User-Agent"));
				PATHS.add(exchange.getRequestURI().getPath());
			}
			exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/1.2.3.json") ? 200 : 404, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	private static String url(String uid, String version) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/" + uid + "/" + version + ".json";
	}

	@Test
	void aKnownVersionResolves() {
		USER_AGENTS.clear();
		PATHS.clear();
		assertTrue(PrismMeta.isResolvable(url("net.minecraftforge", "1.2.3")));
		assertEquals(1, USER_AGENTS.size());
		assertEquals(NetUtils.USER_AGENT, USER_AGENTS.get(0));
	}

	@Test
	void anUnknownVersionIsAPlainMissWithoutARetry() {
		PATHS.clear();
		assertFalse(PrismMeta.isResolvable(url("net.minecraftforge", "9.9.9")));
		assertEquals(1, PATHS.size(), "a 404 verdict must not retry");
	}
}
