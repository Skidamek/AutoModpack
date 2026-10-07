package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.LOADER_MANAGER;
import static pl.skidam.automodpack_core.Constants.MC_VERSION;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStoreException;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.mcholepunch.HolepunchClient;
import pl.skidam.mcholepunch.HolepunchConnection;
import pl.skidam.mcholepunch.HolepunchOptions;
import pl.skidam.mcholepunch.HolepunchRoute;

/**
 * One lane's way onto an endpoint: resolve the route, connect the transport, complete the magic handshake, wrap TLS, and
 * hand the candidate certificate to the trust ladder. It knows nothing about pooling - the pool asks it for a lane and
 * puts the result wherever it fits.
 */
final class LaneConnector {

	record Route(InetSocketAddress directAddress, HolepunchRoute holepunchRoute) {}

	private record TlsCandidate(SSLSocket socket, Socket transport) {}

	private final ConnectionJsons.ConnectionInfo connectionInfo;
	private final Route route;
	private final Function<X509Certificate, CompletableFuture<Boolean>> trustCallback;
	// True the moment the trust ladder asks the player about this client's candidate: only a parked manual-trust
	// decision justifies the handoff liveness probe in open.
	private final AtomicBoolean parkedWhileDeciding = new AtomicBoolean();
	private final CustomizableTrustManager.SessionTrust sessionTrust;
	private final CustomizableTrustManager trustManager;
	private final SSLContext sslContext;

	LaneConnector(ConnectionJsons.ConnectionInfo connectionInfo, Function<X509Certificate, CompletableFuture<Boolean>> trustCallback, Route route) {
		this.connectionInfo = connectionInfo;
		this.route = route;
		this.trustCallback = trustCallback == null ? null : certificate -> {
			parkedWhileDeciding.set(true);
			return trustCallback.apply(certificate);
		};
		this.sessionTrust = new CustomizableTrustManager.SessionTrust(AddressHelpers.formatAddress(connectionInfo.origin), connectionInfo.expectedFingerprint);
		CustomizableTrustManager manager;
		try {
			manager = new CustomizableTrustManager(sessionTrust, null);
		} catch (KeyStoreException e) {
			throw new IllegalStateException("Failed to initialize certificate trust", e);
		}
		// One trust manager and one SSLContext per client, shared by every lane: the JDK's TLS 1.3 session cache lives in
		// the context, so lanes past the first resume (PSK) and never re-enter the trust ladder, and the deferred
		// certificate state keyed per socket in the manager is what makes the sharing safe - concurrent handshakes
		// defer into their own slots instead of a shared per-handshake field.
		this.trustManager = manager;
		this.sslContext = CandidateTrustValidation.newSslContext(manager);
	}

	/** How this client's endpoint is reached: holepunch's resolved route, or a direct address after a brief DNS retry for freshly cut records. */
	static CompletableFuture<Route> resolveRouteAsync(ConnectionJsons.ConnectionInfo connectionInfo) {
		if (connectionInfo.connectionMode == ModpackConnectionMode.HOLEPUNCH) {
			return HolepunchClient.resolve(connectionInfo.endpoint.getHostString(), connectionInfo.endpoint.getPort()).toCompletableFuture()
					.thenApply(holepunchRoute -> new Route(null, holepunchRoute));
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
				if (!address.isUnresolved()) return new Route(address, null);
			}
			throw new CompletionException(failure);
		}, DownloadClient.NET_EXECUTOR);
	}

	/**
	 * Opens one validated lane. Nothing is ever sent to a candidate parked on a manual trust decision - its peer is
	 * exactly the one whose certificate nothing vouches for yet - so a slow decision can outlive the server's idle
	 * reap or a strict host's patience; the handoff probes such a lane once and replaces it with a fresh handshake
	 * that passes the trust the decision just established, so the player is never asked twice.
	 */
	CompletableFuture<Connection> open(String secret, String hostHeader, Runnable slotFreed) {
		return open(secret, hostHeader, slotFreed, true);
	}

	/** The handoff replacement happens once per open, never as a loop: a peer that keeps closing parked lanes gets the fresh handshake and then its ordinary failure. */
	private CompletableFuture<Connection> open(String secret, String hostHeader, Runnable slotFreed, boolean retryHandoff) {
		parkedWhileDeciding.set(false);
		return CompletableFuture.supplyAsync(() -> {
			try {
				return openTlsCandidate();
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, DownloadClient.NET_EXECUTOR).thenCompose(this::validateCandidate).thenComposeAsync(candidate -> {
			if (retryHandoff && parkedWhileDeciding.get() && peerClosed(candidate.socket())) {
				closeQuietly(candidate.socket());
				return open(secret, hostHeader, slotFreed, false);
			}
			try {
				return CompletableFuture.completedFuture(configuredConnection(candidate, secret, hostHeader, slotFreed));
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, DownloadClient.NET_EXECUTOR);
	}

	/**
	 * Whether the peer's close already reached a socket nobody wrote to: a bounded read answers EOF, an unexpected
	 * byte, or an error with dead, and a timeout with alive. Nothing was requested while parked, so a live lane owes
	 * the probe no bytes; the lane configuration sets its own timeout right after either way.
	 */
	private static boolean peerClosed(SSLSocket socket) {
		try {
			socket.setSoTimeout(500);
			try {
				socket.getInputStream().read();
				return true;
			} catch (SocketTimeoutException alive) {
				return false;
			}
		} catch (IOException dead) {
			return true;
		}
	}

	private TlsCandidate openTlsCandidate() throws IOException {
		Socket plainSocket = connectTransport();
		SSLSocket tlsSocket = null;
		try {
			plainSocket.setSoTimeout(NETWORK_TIMEOUT_MILLIS);
			if (connectionInfo.connectionMode == ModpackConnectionMode.MAGIC) performMagicHandshake(plainSocket);
			// TLS identity follows the endpoint - the host this socket actually reaches - so proxied frontends like
			// tunnels present their own certificate (SNI and name check); the origin stays the trust root: its pin
			// is enforced against the presented leaf, and any unpinned first contact defers to the trust ladder.
			tlsSocket = CandidateTrustValidation.wrapWithTls(plainSocket, sslContext, connectionInfo.endpoint.getHostString(), connectionInfo.endpoint.getPort());
			if (plainSocket instanceof HolepunchSocket holepunchSocket) awaitTransportUpgrade(holepunchSocket);
			tlsSocket.setSoTimeout(0);
			return new TlsCandidate(tlsSocket, plainSocket);
		} catch (IOException e) {
			// The candidate never reached validation, so its deferred entry (a handshake that deferred and then died)
			// is spent here; a failure inside startHandshake itself leaves one small entry keyed by the dead socket.
			if (tlsSocket != null) trustManager.forget(tlsSocket);
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
			// Request heads are small writes; without this Nagle holds every head behind its lane's previous segment
			// while the server, which does set TCP_NODELAY, already waits for it.
			socket.setTcpNoDelay(true);
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
			Throwable cause = e.getCause() == null ? e : e.getCause();
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
		return CandidateTrustValidation.validate(new CandidateTrustValidation.Candidate(candidate.socket(), trustManager, sessionTrust, connectionInfo.origin.getHostString(),
				connectionInfo.endpoint.getHostString(), trustCallback)).thenApply(ignored -> candidate);
	}

	/** Turns a validated candidate into a pooled connection, releasing the socket when construction fails. */
	private Connection configuredConnection(TlsCandidate candidate, String secret, String hostHeader, Runnable slotFreed) throws IOException {
		try {
			candidate.socket().setSoTimeout(TRANSFER_IDLE_TIMEOUT_MILLIS);
			return new Connection(candidate.socket(), candidate.transport(), secret, hostHeader, DownloadClient.NET_EXECUTOR, slotFreed);
		} catch (IOException e) {
			closeQuietly(candidate.socket());
			throw e;
		}
	}
}
