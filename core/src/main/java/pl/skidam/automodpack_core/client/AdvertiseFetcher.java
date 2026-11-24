package pl.skidam.automodpack_core.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.WireJsons;
import pl.skidam.automodpack_core.protocol.ContractRoutes;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.utils.AddressHelpers;

/** Fetches GET /advertise over a holepunch TLS lane using the standard trust ladder. */
public final class AdvertiseFetcher {
	private static final int MAX_ADVERTISE_BYTES = 64 * 1024;
	private static final String ESSENTIAL_RELAY_SUFFIX = ".essential-sps";

	private AdvertiseFetcher() {}

	public static boolean isEssentialRelayHost(InetSocketAddress origin) {
		if (origin == null) return false;
		String host = origin.getHostString();
		return host != null && host.toLowerCase(Locale.ROOT).endsWith(ESSENTIAL_RELAY_SUFFIX);
	}

	public static ConnectionJsons.ConnectionInfo resolveAdvertisedConnection(InetSocketAddress origin, InetSocketAddress connectedAddress, String expectedFingerprint, WireJsons.RouteFields route) {
		if (route == null || route.mode == null) return null;
		InetSocketAddress base = connectedAddress == null ? origin : connectedAddress;
		String effectiveHost = route.host == null || route.host.isBlank() ? AddressHelpers.endpointHost(base, origin) : route.host;
		int effectivePort = route.port == null ? base.getPort() : route.port;
		if (effectivePort < 1 || effectivePort > 65535) throw new IllegalArgumentException("Invalid modpack endpoint port: " + effectivePort);
		InetSocketAddress endpoint = AddressHelpers.format(effectiveHost, effectivePort);
		return new ConnectionJsons.ConnectionInfo(origin, endpoint, route.mode, expectedFingerprint);
	}

	public static CompletableFuture<WireJsons.AdvertiseFields> fetchAsync(InetSocketAddress origin, String expectedFingerprint) {
		if (origin == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Origin address is missing"));
		if (isEssentialRelayHost(origin)) return CompletableFuture.failedFuture(new IOException("Essential world hosting relay does not expose a reachable AutoModpack endpoint"));

		ConnectionJsons.ConnectionInfo probeInfo = new ConnectionJsons.ConnectionInfo(origin, origin, ModpackConnectionMode.HOLEPUNCH, expectedFingerprint);
		return DownloadClient.createAsync(probeInfo, null, ManifestFetcher.manualValidationCallbackAsync(probeInfo, true))
				.thenCompose(transport -> {
					Path tempFile;
					try {
						tempFile = Files.createTempFile("automodpack-advertise-", ".json");
					} catch (IOException e) {
						transport.close();
						return CompletableFuture.failedFuture(e);
					}
					BoundedByteArrayOutputStream buffer = new BoundedByteArrayOutputStream(MAX_ADVERTISE_BYTES);
					return transport.downloadDocument(ContractRoutes.ADVERTISE_KEY.getBytes(StandardCharsets.UTF_8), tempFile, null, buffer)
							.thenApply(fetch -> WireJsons.AdvertiseFields.fromJson(buffer.toString(StandardCharsets.UTF_8)))
							.whenComplete((ad, error) -> {
								transport.close();
								try {
									Files.deleteIfExists(tempFile);
								} catch (IOException ignored) {
								}
							});
				});
	}

	private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
		private final int limit;

		BoundedByteArrayOutputStream(int limit) {
			this.limit = limit;
		}

		@Override
		public synchronized void write(int b) {
			if (count + 1 > limit) throw new IllegalStateException("Advertise payload exceeded " + limit + " bytes");
			super.write(b);
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) {
			if (count + len > limit) throw new IllegalStateException("Advertise payload exceeded " + limit + " bytes");
			super.write(b, off, len);
		}
	}
}
