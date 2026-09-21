package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * LIVE test: drives the real contract server and the real DownloadClient through a cloudflared quick tunnel
 * (`cloudflared tunnel --url http://localhost:8000`). Requires outbound internet and the cloudflared binary;
 * skipped (not failed) when either is missing, so CI stays hermetic.
 */
class CloudflaredTunnelLiveTest {
	private static final int AWAIT_SECONDS = 60;

	@Test
	void contractSurvivesCloudflaredTunnel(@TempDir Path directory) throws Exception {
		Assumptions.assumeTrue(hasCloudflared(), "cloudflared is not installed; skipping");

		Path hostDir = directory.resolve("hosting");
		Files.createDirectories(hostDir);
		byte[] head = "tunnel-head-document\n".repeat(64).getBytes(StandardCharsets.UTF_8);
		byte[] object = "tunnel-object-payload-that-compresses-well\n".repeat(4096).getBytes(StandardCharsets.UTF_8);
		Files.write(hostDir.resolve("head"), head);
		String objectHash = HashUtils.sha1(object);
		Files.write(hostDir.resolve("object"), object);

		Map<String, Path> paths = new HashMap<>();
		paths.put(GenerationHosting.HEAD_DOCUMENT_KEY, hostDir.resolve("head"));
		paths.put(objectHash, hostDir.resolve("object"));

		ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
		config.connectionMode = ModpackConnectionMode.HTTP;
		config.bindAddress = "127.0.0.1";
		config.bindPort = 0;
		config.disableInternalTLS = true; // cloudflared terminates TLS; the origin hop is plain HTTP
		config.validateSecrets = false;
		ServerConfigJsons.ServerConfigFieldsV3 previous = Constants.serverConfig;
		Constants.serverConfig = config;
		NettyServer server = new NettyServer();
		try {
			server.replacePaths(paths);
			var bound = server.start();
			assertTrue(bound.isPresent(), "contract listener must bind");
			int originPort = ((InetSocketAddress) bound.get().channel().localAddress()).getPort();

			Path cloudflaredLog = directory.resolve("cloudflared.log");
			Process tunnel = new ProcessBuilder("cloudflared", "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:" + originPort)
					.redirectErrorStream(true).redirectOutput(cloudflaredLog.toFile()).start();
			try {
				String publicUrl = awaitTunnelUrl(cloudflaredLog);
				Assumptions.assumeTrue(publicUrl != null, "cloudflared did not cut a quick tunnel (offline or rate-limited); skipping");
				String host = publicUrl.substring("https://".length());
				awaitDns(host);
				InetSocketAddress endpoint = AddressHelpers.format(host, 443);
				ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(endpoint, endpoint, ModpackConnectionMode.HTTP, null, null);
				// The edge certificate is publicly trusted, but a first contact always pins with the player's blessing.
				DownloadClient client = DownloadClient.createAsync(connectionInfo, null, ignored -> CompletableFuture.completedFuture(true))
						.get(AWAIT_SECONDS, TimeUnit.SECONDS);
				try {
					Path headDestination = directory.resolve("head-download");
					client.downloadDocument(GenerationHosting.HEAD_DOCUMENT_KEY.getBytes(StandardCharsets.UTF_8), headDestination, null, (IntConsumer) null)
							.get(AWAIT_SECONDS, TimeUnit.SECONDS);
					assertArrayEquals(head, Files.readAllBytes(headDestination));

					Path objectDestination = directory.resolve("object-download");
					client.downloadObject(objectHash.getBytes(StandardCharsets.UTF_8), objectDestination, object.length, null)
							.get(AWAIT_SECONDS, TimeUnit.SECONDS);
					assertArrayEquals(object, Files.readAllBytes(objectDestination));

					// A resumed transfer appends exactly behind the stored prefix, through the same tunnel.
					Path rangedDestination = directory.resolve("object-ranged");
					long offset = 4096;
					Files.write(rangedDestination, Arrays.copyOf(object, (int) offset));
					client.downloadObject(objectHash.getBytes(StandardCharsets.UTF_8), rangedDestination, object.length, null)
							.get(AWAIT_SECONDS, TimeUnit.SECONDS);
					assertArrayEquals(object, Files.readAllBytes(rangedDestination));
				} finally {
					client.close();
				}
			} finally {
				tunnel.destroy();
			}
		} finally {
			server.stop();
			Constants.serverConfig = previous;
		}
	}

	/** Quick-tunnel names are cut seconds before use; wait for the record to reach the resolver. */
	private static void awaitDns(String host) throws Exception {
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(45).toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (!InetSocketAddress.createUnresolved(host, 443).isUnresolved()) return;
			Thread.sleep(1000);
		}
	}

	/** Waits for cloudflared to print the public quick-tunnel URL. */
	private static String awaitTunnelUrl(Path log) throws Exception {
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(45).toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (Files.exists(log)) {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(log), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						int index = line.indexOf("https://");
						if (index >= 0 && line.contains("trycloudflare.com")) {
							String url = line.substring(index);
							int end = url.indexOf('|');
							return (end >= 0 ? url.substring(0, end) : url).trim();
						}
					}
				}
			}
			Thread.sleep(500);
		}
		return null;
	}

	private static boolean hasCloudflared() {
		try {
			Process which = new ProcessBuilder("sh", "-c", "command -v cloudflared").start();
			boolean found = which.waitFor(10, TimeUnit.SECONDS) && which.exitValue() == 0;
			which.destroyForcibly();
			return found;
		} catch (Exception e) {
			return false;
		}
	}
}
