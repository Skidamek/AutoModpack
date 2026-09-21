package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.IntConsumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.Throwables;
import pl.skidam.mcholepunch.HolepunchClient;
import pl.skidam.mcholepunch.HolepunchConnection;
import pl.skidam.mcholepunch.HolepunchOptions;
import pl.skidam.mcholepunch.HolepunchRoute;

public class DownloadClient implements PackTransport {

	/** The transport's own async callbacks (connection IO, manifest and platform fetches); app work belongs to the app's executor. */
	public static final ExecutorService NET_EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "automodpack-net");
		t.setDaemon(true);
		return t;
	});

	private static final int MAX_CONNECTIONS = 5;

	private final ConnectionJsons.ConnectionInfo connectionInfo;
	private final String secret;
	private final Function<X509Certificate, CompletableFuture<Boolean>> trustCallback;
	private final Duration preConfigurationKeepaliveInterval;
	private final CustomizableTrustManager.SessionTrust sessionTrust;
	private final TransportRoute route;
	private final Object poolLock = new Object();
	// The lanes, in creation order: worker i submits to lane i when it has a free slot, so the scheduler's largest-first
	// dispatch puts concurrent big files on distinct lanes while small files fill each lane's depth. ≤ 8 × 10 KB = 80 KB
	// can queue behind one large response; the scheduler dispatches large files to their own workers first, and a
	// non-draining peer trips the 90 s stall window on its lane alone.
	private final List<Connection> lanes = new ArrayList<>();
	private final Deque<SlotWaiter<?>> slotWaiters = new ArrayDeque<>();
	private int openingConnections;
	private volatile boolean closed;

	private record TransportRoute(InetSocketAddress directAddress, HolepunchRoute holepunchRoute) {}

	private record TlsCandidate(SSLSocket socket, Socket transport, CustomizableTrustManager trustManager) {}

	private DownloadClient(ConnectionJsons.ConnectionInfo connectionInfo, String secret, Function<X509Certificate, CompletableFuture<Boolean>> trustCallback,
			Duration preConfigurationKeepaliveInterval, TransportRoute route) {
		this.connectionInfo = connectionInfo;
		this.secret = secret;
		this.trustCallback = trustCallback;
		this.preConfigurationKeepaliveInterval = preConfigurationKeepaliveInterval;
		this.route = route;
		this.sessionTrust = new CustomizableTrustManager.SessionTrust(AddressHelpers.formatAddress(connectionInfo.origin), connectionInfo.expectedFingerprint);
	}

	public static CompletableFuture<DownloadClient> createAsync(ConnectionJsons.ConnectionInfo connectionInfo, String secret,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
		return createAsync(connectionInfo, secret, trustCallback, PRE_CONFIGURATION_KEEPALIVE_INTERVAL);
	}

	/** The keepalive interval is injectable so tests can observe heartbeats at a fast cadence; production runs at {@link NetUtils#PRE_CONFIGURATION_KEEPALIVE_INTERVAL}. */
	static CompletableFuture<DownloadClient> createAsync(ConnectionJsons.ConnectionInfo connectionInfo, String secret,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback, Duration preConfigurationKeepaliveInterval) {
		if (connectionInfo == null || !connectionInfo.isComplete())
			return CompletableFuture.failedFuture(new IllegalArgumentException("Connection origin or endpoint is missing"));

		return resolveRouteAsync(connectionInfo).thenCompose(route -> {
			DownloadClient client = new DownloadClient(connectionInfo, secret, trustCallback, preConfigurationKeepaliveInterval, route);
			return client.openConnectionAsync().thenApply(connection -> {
				synchronized (client.poolLock) {
					client.lanes.add(connection);
				}
				return client;
			}).whenComplete((ignored, error) -> {
				if (error != null) client.close();
			});
		});
	}

	private static CompletableFuture<TransportRoute> resolveRouteAsync(ConnectionJsons.ConnectionInfo connectionInfo) {
		if (connectionInfo.connectionMode == ModpackConnectionMode.HOLEPUNCH) {
			return HolepunchClient.resolve(connectionInfo.endpoint.getHostString(), connectionInfo.endpoint.getPort()).toCompletableFuture()
					.thenApply(route -> new TransportRoute(null, route));
		}

		return CompletableFuture.supplyAsync(() -> {
			String host = connectionInfo.endpoint.getHostString();
			// Freshly-cut DNS records (quick tunnels cut their name seconds before the first player connects) can lag
			// the advertisement; retry briefly before declaring the endpoint unresolvable.
			IOException failure = new IOException("Failed to resolve endpoint host: " + host);
			for (int attempt = 0; attempt < 3; attempt++) {
				if (attempt > 0) {
					try {
						Thread.sleep(1500);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new CompletionException(failure);
					}
				}
				InetSocketAddress address = new InetSocketAddress(host, connectionInfo.endpoint.getPort());
				if (!address.isUnresolved()) return new TransportRoute(address, null);
			}
			throw new CompletionException(failure);
		}, DownloadClient.NET_EXECUTOR);
	}

	private CompletableFuture<Connection> openConnectionAsync() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return openTlsCandidate();
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, NET_EXECUTOR).thenCompose(this::validateCandidate).thenApplyAsync(candidate -> {
			try {
				return configuredConnection(candidate);
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, DownloadClient.NET_EXECUTOR);
	}

	private TlsCandidate openTlsCandidate() throws IOException {
		CustomizableTrustManager trustManager;
		try {
			trustManager = new CustomizableTrustManager(sessionTrust, null);
		} catch (Exception e) {
			throw new IOException("Failed to initialize certificate trust", e);
		}
		SSLContext context = CandidateTrustValidation.newSslContext(trustManager);
		Socket plainSocket = connectTransport();

		try {
			plainSocket.setSoTimeout(NETWORK_TIMEOUT_MILLIS);
			if (connectionInfo.connectionMode == ModpackConnectionMode.MAGIC) performMagicHandshake(plainSocket);
			// TLS identity follows the endpoint - the host this socket actually reaches - so proxied frontends like
			// tunnels present their own valid certificate (SNI and name check); the origin stays the trust root via
			// its fingerprint pin, and the self-signed deferral ladder is unaffected.
			SSLSocket tlsSocket = CandidateTrustValidation.wrapWithTls(plainSocket, context, connectionInfo.endpoint.getHostString(), connectionInfo.endpoint.getPort());
			if (plainSocket instanceof HolepunchSocket holepunchSocket) awaitTransportUpgrade(holepunchSocket);
			tlsSocket.setSoTimeout(0);
			return new TlsCandidate(tlsSocket, plainSocket, trustManager);
		} catch (IOException e) {
			closeQuietly(plainSocket);
			throw e;
		}
	}

	private void awaitTransportUpgrade(HolepunchSocket socket) throws IOException {
		try {
			socket.enableTlsTrafficCamouflage(true);
			socket.commitTransportUpgrade().toCompletableFuture().get(NETWORK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Holepunch transport upgrade interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw new IOException("Holepunch transport handoff failed", cause);
		} catch (TimeoutException e) {
			throw new IOException("Holepunch transport handoff timed out", e);
		}
	}

	private static HolepunchOptions holepunchOptions() {
		HolepunchOptions.Builder builder = HolepunchOptions.builder().connectTimeout(NETWORK_TIMEOUT).negotiationTimeout(NETWORK_TIMEOUT);
		LoaderManagerService.ModPlatform platform = LOADER_MANAGER.getPlatformType();
		if (platform == LoaderManagerService.ModPlatform.FORGE || platform == LoaderManagerService.ModPlatform.NEOFORGE) {
			builder.handshakeHostSuffix(HolepunchOptions.FORGE_FML3_HANDSHAKE_HOST_SUFFIX);
		}
		return builder.build();
	}

	private Socket connectTransport() throws IOException {
		if (connectionInfo.connectionMode != ModpackConnectionMode.HOLEPUNCH) {
			Socket socket = new Socket();
			socket.connect(route.directAddress(), NETWORK_TIMEOUT_MILLIS);
			// Helps plain TCP NAT mappings survive the parked trust decision; zero protocol impact.
			socket.setKeepAlive(true);
			return socket;
		}

		int protocolVersion = MinecraftProtocols.forVersion(MC_VERSION);

		try {
			HolepunchSocket socket = new HolepunchSocket();
			HolepunchConnection connection = HolepunchClient.connect(route.holepunchRoute(), protocolVersion, socket.handler(), holepunchOptions())
					.toCompletableFuture().get(NETWORK_TIMEOUT.plus(NETWORK_TIMEOUT).toSeconds(), TimeUnit.SECONDS);
			socket.setConnection(connection);
			return socket;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Holepunch connect interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof IOException io) throw io;
			throw new IOException("Holepunch connect failed", cause);
		} catch (Exception e) {
			throw new IOException("Holepunch connect failed", e);
		}
	}

	private void performMagicHandshake(Socket plainSocket) throws IOException {
		DataOutputStream plainOut = new DataOutputStream(new BufferedOutputStream(plainSocket.getOutputStream()));
		DataInputStream plainIn = new DataInputStream(new BufferedInputStream(plainSocket.getInputStream()));
		byte[] hostBytes = connectionInfo.endpoint.getHostString().getBytes(StandardCharsets.UTF_8);

		plainOut.writeInt(MAGIC_AMMH);
		plainOut.writeShort(hostBytes.length);
		plainOut.write(hostBytes);
		plainOut.flush();

		int handshakeResponse = plainIn.readInt();
		if (handshakeResponse != MAGIC_AMOK) throw new IOException("Invalid response from server: " + handshakeResponse);
	}

	/** The shared candidate trust ladder; completion means the certificate is pinned into this session's trust. */
	private CompletableFuture<TlsCandidate> validateCandidate(TlsCandidate candidate) {
		return CandidateTrustValidation.validate(new CandidateTrustValidation.Candidate(candidate.socket(), candidate.trustManager(), sessionTrust, connectionInfo.origin.getHostString(),
				connectionInfo.endpoint.getHostString(), trustCallback, () -> !closed, hostHeader(), secret), preConfigurationKeepaliveInterval).thenApply(ignored -> candidate);
	}

	/** Turns a validated candidate into a pooled connection, releasing the socket when construction fails. */
	private Connection configuredConnection(TlsCandidate candidate) throws IOException {
		try {
			candidate.socket().setSoTimeout(TRANSFER_IDLE_TIMEOUT_MILLIS);
			return new Connection(candidate.socket(), candidate.transport(), secret, hostHeader(), NET_EXECUTOR, this::slotFreed);
		} catch (IOException e) {
			closeQuietly(candidate.socket());
			throw e;
		}
	}

	private String hostHeader() {
		return connectionInfo.endpoint.getHostString() + ":" + connectionInfo.endpoint.getPort();
	}

	/** Queues a submit on a lane with a free slot; a new lane opens when all of them are full, past which the waiter waits. */
	private <T> CompletableFuture<T> withSlot(int lane, Function<Connection, CompletableFuture<T>> operation) {
		CompletableFuture<T> future = new CompletableFuture<>();
		synchronized (poolLock) {
			if (closed) {
				future.completeExceptionally(new IOException("Download client is closed"));
				return future;
			}
			slotWaiters.add(new SlotWaiter<>(lane, operation, future));
			pumpPool();
		}
		return future;
	}

	private void pumpPool() {
		reapLanes();
		while (!slotWaiters.isEmpty()) {
			Connection connection = pick(slotWaiters.peek().lane());
			if (connection == null) break;
			slotWaiters.remove().dispatch(connection);
		}
		while (!closed && !slotWaiters.isEmpty() && lanes.size() + openingConnections < MAX_CONNECTIONS) {
			SlotWaiter<?> waiter = slotWaiters.remove();
			openingConnections++;
			openConnectionAsync().whenComplete((connection, error) -> {
				synchronized (poolLock) {
					openingConnections--;
					if (closed) {
						if (connection != null) closeQuietly(connection);
						waiter.future().completeExceptionally(new IOException("Download client is closed"));
					} else if (error != null) {
						WireTrace.log("LANE_FAIL", "lane", waiter.lane(), "error", Throwables.unwrap(error));
						waiter.future().completeExceptionally(Throwables.unwrap(error));
					} else {
						lanes.add(connection);
						WireTrace.log("LANE_OPEN", "lanes", lanes.size(), "conn", connection.traceId());
						waiter.dispatch(connection);
					}
					pumpPool();
				}
			});
		}
	}

	/** Lane i serves waiter i when it has a free slot; otherwise the first lane with one does. Null means every lane is full or gone. */
	private Connection pick(int lane) {
		if (lanes.isEmpty()) return null;
		if (lane < lanes.size() && lanes.get(lane).hasFreeSlot()) return lanes.get(lane);
		for (Connection connection : lanes) {
			if (connection.hasFreeSlot()) return connection;
		}
		return null;
	}

	private void reapLanes() {
		for (Iterator<Connection> iterator = lanes.iterator(); iterator.hasNext();) {
			Connection connection = iterator.next();
			if (!connection.isActive()) {
				iterator.remove();
				WireTrace.log("LANE_REAP", "conn", connection.traceId(), "lanes", lanes.size());
				closeQuietly(connection);
			}
		}
	}

	/** A response completed somewhere, so a slot freed; waiters queued on the pool get their chance. */
	private void slotFreed() {
		synchronized (poolLock) {
			if (!closed) pumpPool();
		}
	}

	private record SlotWaiter<T>(int lane, Function<Connection, CompletableFuture<T>> operation, CompletableFuture<T> future) {
		void dispatch(Connection connection) {
			CompletableFuture<T> result;
			try {
				result = operation.apply(connection);
			} catch (Exception e) {
				result = CompletableFuture.failedFuture(e);
			}
			result.whenComplete((value, error) -> {
				if (error != null) future.completeExceptionally(error);
				else future.complete(value);
			});
		}
	}

	public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
		return downloadFile(fileHash, destination, 0, chunkCallback, 0);
	}

	@Override
	public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, long offset, IntConsumer chunkCallback) {
		return downloadFile(fileHash, destination, offset, chunkCallback, 0);
	}

	@Override
	public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, long offset, IntConsumer chunkCallback, int lane) {
		return downloadFile(fileHash, destination, offset, -1L, chunkCallback, lane);
	}

	@Override
	public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, long offset, long endInclusive, IntConsumer chunkCallback, int lane) {
		// A stale range already surfaces as StaleRangeException from the response parse; no mapping happens here.
		return withSlot(lane, connection -> connection.sendDownloadFile(fileHash, destination, chunkCallback, offset, endInclusive));
	}

	/** Document fetch (reserved keys); when {@code expectedSha1Hex} (lowercase hex) matches the served document the server answers 304 and {@code destination} is not written. */
	@Override
	public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer chunkCallback) {
		return withSlot(0, connection -> connection.sendDownloadDocument(key, destination, expectedSha1Hex, chunkCallback, null));
	}

	/** The same fetch with a tap: served body bytes reach the tap (decode-while-downloading) and the destination alike. */
	@Override
	public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, OutputStream tap) {
		return withSlot(0, connection -> connection.sendDownloadDocument(key, destination, expectedSha1Hex, null, tap));
	}

	/** Drops every pooled and in-flight transfer connection so a cancelled run cannot poison the next one. */
	@Override
	public void abortTransfers() {
		List<Connection> connections;
		synchronized (poolLock) {
			if (closed) return;
			connections = new ArrayList<>(lanes);
			lanes.clear();
		}
		connections.forEach(DownloadClient::closeQuietly);
		synchronized (poolLock) {
			if (!closed) pumpPool();
		}
	}

	@Override
	public int pipelineCapacity() {
		return MAX_CONNECTIONS * Connection.PIPELINE_DEPTH;
	}

	static void closeQuietly(AutoCloseable closeable) {
		CandidateTrustValidation.closeQuietly(closeable);
	}

	@Override
	public void close() {
		List<Connection> connections;
		List<SlotWaiter<?>> waiters;
		synchronized (poolLock) {
			if (closed) return;
			closed = true;
			connections = new ArrayList<>(lanes);
			waiters = new ArrayList<>(slotWaiters);
			lanes.clear();
			slotWaiters.clear();
		}

		IOException closedError = new IOException("Download client is closed");
		waiters.forEach(waiter -> waiter.future().completeExceptionally(closedError));
		connections.forEach(DownloadClient::closeQuietly);
	}
}
