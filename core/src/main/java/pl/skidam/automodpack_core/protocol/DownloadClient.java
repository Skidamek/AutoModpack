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
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
	private final byte[] secretBytes;
	private final Function<X509Certificate, CompletableFuture<Boolean>> trustCallback;
	private final Duration preConfigurationKeepaliveInterval;
	private final CustomizableTrustManager.SessionTrust sessionTrust;
	private final TransportRoute route;
	private final Object poolLock = new Object();
	private final Deque<Connection> availableConnections = new ArrayDeque<>();
	private final Deque<CompletableFuture<Connection>> connectionWaiters = new ArrayDeque<>();
	private final Set<Connection> allConnections = Collections.newSetFromMap(new IdentityHashMap<>());
	private int openingConnections;
	private volatile boolean closed;

	private record TransportRoute(InetSocketAddress directAddress, HolepunchRoute holepunchRoute) {}

	private record TlsCandidate(SSLSocket socket, Socket transport, CustomizableTrustManager trustManager) {}

	private DownloadClient(ConnectionJsons.ConnectionInfo connectionInfo, byte[] secretBytes, Function<X509Certificate, CompletableFuture<Boolean>> trustCallback,
			Duration preConfigurationKeepaliveInterval, TransportRoute route) {
		this.connectionInfo = connectionInfo;
		this.secretBytes = secretBytes == null ? null : secretBytes.clone();
		this.trustCallback = trustCallback;
		this.preConfigurationKeepaliveInterval = preConfigurationKeepaliveInterval;
		this.route = route;
		this.sessionTrust = new CustomizableTrustManager.SessionTrust(AddressHelpers.formatAddress(connectionInfo.origin), connectionInfo.expectedFingerprint);
	}

	public static CompletableFuture<DownloadClient> createAsync(ConnectionJsons.ConnectionInfo connectionInfo, byte[] secretBytes,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
		return createAsync(connectionInfo, secretBytes, trustCallback, PRE_CONFIGURATION_KEEPALIVE_INTERVAL);
	}

	/** The keepalive interval is injectable so tests can observe heartbeats at a fast cadence; production runs at {@link NetUtils#PRE_CONFIGURATION_KEEPALIVE_INTERVAL}. */
	static CompletableFuture<DownloadClient> createAsync(ConnectionJsons.ConnectionInfo connectionInfo, byte[] secretBytes,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback, Duration preConfigurationKeepaliveInterval) {
		if (connectionInfo == null || !connectionInfo.isComplete())
			return CompletableFuture.failedFuture(new IllegalArgumentException("Connection origin or endpoint is missing"));

		return resolveRouteAsync(connectionInfo).thenCompose(route -> {
			DownloadClient client = new DownloadClient(connectionInfo, secretBytes, trustCallback, preConfigurationKeepaliveInterval, route);
			return client.openConnectionAsync().thenApply(connection -> {
				synchronized (client.poolLock) {
					client.allConnections.add(connection);
					client.availableConnections.add(connection);
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
			InetSocketAddress address = new InetSocketAddress(host, connectionInfo.endpoint.getPort());
			if (address.isUnresolved()) throw new CompletionException(new IOException("Failed to resolve endpoint host: " + host));
			return new TransportRoute(address, null);
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
			SSLSocket tlsSocket = CandidateTrustValidation.wrapWithTls(plainSocket, context, connectionInfo.origin.getHostString(), connectionInfo.endpoint.getPort());
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
				connectionInfo.endpoint.getHostString(), trustCallback, () -> !closed), preConfigurationKeepaliveInterval).thenApply(ignored -> candidate);
	}

	/** Turns a validated candidate into a configured connection, releasing the socket when the negotiation fails. */
	private Connection configuredConnection(TlsCandidate candidate) throws IOException {
		try {
			candidate.socket().setSoTimeout(TRANSFER_IDLE_TIMEOUT_MILLIS);
			return new Connection(candidate.socket(), candidate.transport(), secretBytes, NET_EXECUTOR);
		} catch (IOException e) {
			closeQuietly(candidate.socket());
			throw e;
		}
	}

	private CompletableFuture<Connection> acquireConnection() {
		CompletableFuture<Connection> waiter = new CompletableFuture<>();
		synchronized (poolLock) {
			if (closed) return CompletableFuture.failedFuture(new IOException("Download client is closed"));
			connectionWaiters.add(waiter);
			pumpPool();
		}
		return waiter;
	}

	private void pumpPool() {
		while (!availableConnections.isEmpty()) {
			Connection connection = availableConnections.peek();
			if (connection.isActive()) break;
			availableConnections.remove();
			allConnections.remove(connection);
			closeQuietly(connection);
		}

		while (!connectionWaiters.isEmpty() && !availableConnections.isEmpty()) {
			CompletableFuture<Connection> waiter = connectionWaiters.remove();
			Connection connection = availableConnections.remove();
			waiter.complete(connection);
		}

		while (!closed && !connectionWaiters.isEmpty() && allConnections.size() + openingConnections < MAX_CONNECTIONS) {
			CompletableFuture<Connection> waiter = connectionWaiters.remove();
			openingConnections++;
			openConnectionAsync().whenComplete((connection, error) -> {
				synchronized (poolLock) {
					openingConnections--;
					if (closed) {
						if (connection != null) closeQuietly(connection);
						waiter.completeExceptionally(new IOException("Download client is closed"));
					} else if (error != null) {
						waiter.completeExceptionally(Throwables.unwrap(error));
					} else {
						allConnections.add(connection);
						waiter.complete(connection);
					}
					pumpPool();
				}
			});
		}
	}

	private <T> CompletableFuture<T> withConnection(Function<Connection, CompletableFuture<T>> operation) {
		return acquireConnection().thenCompose(connection -> {
			CompletableFuture<T> future;
			try {
				future = operation.apply(connection);
			} catch (Exception e) {
				future = CompletableFuture.failedFuture(e);
			}
			return future.whenComplete((ignored, error) -> releaseConnection(connection, error == null));
		});
	}

	private void releaseConnection(Connection connection, boolean healthy) {
		synchronized (poolLock) {
			if (closed || !healthy || !connection.isActive()) {
				allConnections.remove(connection);
				availableConnections.remove(connection);
				closeQuietly(connection);
			} else {
				availableConnections.add(connection);
			}
			pumpPool();
		}
	}

	public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
		return downloadFile(fileHash, destination, 0, chunkCallback);
	}

	@Override
	public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, long offset, IntConsumer chunkCallback) {
		return withConnection(connection -> connection.sendDownloadFile(fileHash, destination, chunkCallback, null, offset, null)).exceptionally(error -> {
			Throwable cause = Throwables.unwrap(error);
			// A valid range the object can no longer satisfy is the stale-partial verdict, not an ordinary remote failure.
			if (cause instanceof IOException io && ("Server error: " + StaleRangeException.WIRE_MESSAGE).equals(io.getMessage()))
				throw new CompletionException(new StaleRangeException());
			if (error instanceof RuntimeException runtime) throw runtime;
			if (error instanceof Error failure) throw failure;
			throw new CompletionException(error);
		});
	}

	/** Document fetch (reserved keys); when {@code expectedSha1Hex} (lowercase hex) matches the served document the server answers UNCHANGED and {@code destination} is not written. */
	@Override
	public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer chunkCallback) {
		return withConnection(connection -> connection.sendDownloadDocument(key, destination, expectedSha1Hex == null ? null : expectedSha1Hex.getBytes(StandardCharsets.UTF_8), chunkCallback));
	}

	/** Drops every pooled and in-flight transfer connection so a cancelled run cannot poison the next one. */
	@Override
	public void abortTransfers() {
		List<Connection> connections;
		synchronized (poolLock) {
			if (closed) return;
			connections = new ArrayList<>(allConnections);
			allConnections.clear();
			availableConnections.clear();
		}
		connections.forEach(DownloadClient::closeQuietly);
		synchronized (poolLock) {
			if (!closed) pumpPool();
		}
	}

	static void closeQuietly(AutoCloseable closeable) {
		CandidateTrustValidation.closeQuietly(closeable);
	}

	@Override
	public void close() {
		List<Connection> connections;
		List<CompletableFuture<Connection>> waiters;
		synchronized (poolLock) {
			if (closed) return;
			closed = true;
			connections = new ArrayList<>(allConnections);
			waiters = new ArrayList<>(connectionWaiters);
			allConnections.clear();
			availableConnections.clear();
			connectionWaiters.clear();
		}

		IOException closedError = new IOException("Download client is closed");
		waiters.forEach(waiter -> waiter.completeExceptionally(closedError));
		connections.forEach(DownloadClient::closeQuietly);
	}
}
