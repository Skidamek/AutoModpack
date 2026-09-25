package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.utils.DownloadSource;

/**
 * A platform body that matches its own Content-Length but not the advertised object size is a remote failure, not a
 * successful download that later fails assemble as local storage.
 */
class HttpFileDownloaderTest {

	private static HttpServer server;
	private static final byte[] FULL = "the-advertised-object-bytes".getBytes();

	@BeforeAll
	static void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "http-file-downloader-test");
			t.setDaemon(true);
			return t;
		}));
		server.createContext("/short", exchange -> {
			byte[] prefix = new byte[FULL.length / 2];
			System.arraycopy(FULL, 0, prefix, 0, prefix.length);
			exchange.sendResponseHeaders(200, prefix.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(prefix);
			}
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void stopServer() {
		server.stop(0);
	}

	@Test
	void aShorterThanAdvertisedBodyFailsAsARemoteError(@TempDir Path directory) throws Exception {
		Path destination = directory.resolve("object");
		DownloadSource source = new DownloadSource("http://127.0.0.1:" + server.getAddress().getPort() + "/short", DownloadSource.Provider.MODRINTH);
		IOException thrown = assertThrows(IOException.class, () -> new HttpFileDownloader().download(source, destination, FULL.length, 0, null));
		assertTrue(thrown.getMessage().contains("ended at"), thrown.getMessage());
		assertFalse(PartialResume.complete(destination, FULL.length), "the short body must not look complete");
	}
}
