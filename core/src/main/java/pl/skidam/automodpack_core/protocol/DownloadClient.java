package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

	/** Every download worker owns one pipeline lane; big files never queue behind another on the same lane. The download manager sizes its worker pool from this single source. */
	public static final int MAX_CONNECTIONS = 5;

	private final ConnectionJsons.ConnectionInfo connectionInfo;
	private final String secret;
	private final Function<X509Certificate, CompletableFuture<Boolean>> trustCallback;
	private final Duration preConfigurationKeepaliveInterval;
	private final CustomizableTrustManager.SessionTrust sessionTrust;
	private final TransportRoute route;
	// The transport's wire window: the number of unsettled takes it may keep on the lanes. The cap is the receipted
	// lanes × pipeline depth; the pacer starts at one lane's depth, grows while throughput climbs and halves on failure.
	private final WirePacer pacer = new WirePacer(MAX_CONNECTIONS * Connection.PIPELINE_DEPTH, MAX_CONNECTIONS);
	// The live transfers, so a settle anywhere revives one whose takes all settled while the window was full - a dormant
	// transfer has nothing in flight, so nothing else would ever hand the freed credit to it. Own lock: never taken
	// while holding a transfer's lock or the pool lock.
	private final Object transferRegistryLock = new Object();
	private final List<ObjectTransfer> activeTransfers = new ArrayList<>();
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
		// One SSLContext per lane, so lanes past the first pay a full TLS handshake each: a shared context would let
		// the JDK session cache resume them, but the deferred per-handshake certificate state lives in the trust
		// manager, so sharing one needs a delegating trust manager. A couple of RTTs per sync is not that price.
		SSLContext context = CandidateTrustValidation.newSslContext(trustManager);
		Socket plainSocket = connectTransport();

		try {
			plainSocket.setSoTimeout(NETWORK_TIMEOUT_MILLIS);
			if (connectionInfo.connectionMode == ModpackConnectionMode.MAGIC) performMagicHandshake(plainSocket);
			// TLS identity follows the endpoint - the host this socket actually reaches - so proxied frontends like
			// tunnels present their own certificate (SNI and name check); the origin stays the trust root: its pin
			// is enforced against the presented leaf, and any unpinned first contact defers to the trust ladder.
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

	/**
	 * One complete object transfer: on success the destination holds the FULL object bytes. Resume validation and the
	 * zero-size shortcut run on the calling thread exactly as the old manager's dispatch did; the wire credit for the
	 * first take is taken here too, so a full window fails the future before anything is sent and the caller requeues.
	 */
	@Override
	public CompletableFuture<Path> downloadObject(byte[] sha1Hex, Path destination, long fileSize, IntConsumer progress) {
		try {
			if (fileSize == 0) {
				// Zero-size objects never reach the wire: materialize the empty object and let the caller's promotion judge it.
				if (Files.exists(destination) && Files.size(destination) > 0) Files.delete(destination);
				if (!Files.exists(destination)) Files.createFile(destination);
				return CompletableFuture.completedFuture(destination);
			}
			long offset = resumeOffset(destination, fileSize);
			if (offset >= fileSize) {
				// A complete-sized destination skips the network; the caller's promotion judges it for free.
				return CompletableFuture.completedFuture(destination);
			}
			if (!pacer.tryAcquire()) {
				// The caller requeues on this type without burning retry budget; no credit is held, so none is released.
				return CompletableFuture.failedFuture(new WireWindowFullException());
			}
			return new ObjectTransfer(sha1Hex, destination, fileSize, offset, progress).start();
		} catch (IOException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** The byte offset the transfer resumes from: the destination's size while it is a valid prefix, else a fresh start. */
	private static long resumeOffset(Path destination, long fileSize) {
		if (!Files.exists(destination)) return 0;
		long size;
		try {
			size = Files.size(destination);
		} catch (IOException e) {
			LOGGER.warn("Failed to inspect the partial {}; restarting from zero", destination.getFileName(), e);
			deleteQuietly(destination);
			return 0;
		}
		if (size > fileSize) {
			LOGGER.warn("Stored partial for {} is past the served object's end; restarting from zero", destination.getFileName());
			deleteQuietly(destination);
			return 0;
		}
		return size;
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}

	/**
	 * One transfer's tiling, window accounting and completion barrier. The first take rides the credit acquired at
	 * dispatch and covers the streamer's head chunk; every further take acquires its own credit and claims the
	 * uncovered tail. All bookkeeping runs inside the transfer lock, one thread at a time; the pacer lock is always
	 * taken either alone or inside the transfer lock, never the other way round.
	 */
	private final class ObjectTransfer {
		private final byte[] sha1Hex;
		private final Path destination;
		private final long fileSize;
		// The resume point: the streamer owns [offset, floor) and the tail takes claim everything behind it.
		private final long offset;
		private final IntConsumer progress;
		private final CompletableFuture<Path> future = new CompletableFuture<>();
		private final Object lock = new Object();
		// Per-transfer round-robin lane hint: takes spread over the lanes the way the pool spreads workers, and pick()
		// still falls back to any lane with a free slot. The simplest correct hint.
		private final AtomicInteger laneCounter = new AtomicInteger();
		// The cursor is the transfer's one uncovered-tail pointer: the streamer owns [offset, floor) and every take
		// claims exactly [stealFrom, old cursor - 1], so the final take may be short - a whole-chunk walk past a
		// non-chunk-multiple size would orphan bytes no request ever covers and the barrier would never fire.
		private long cursor;
		private int pendingItems;
		// First error wins: recorded once, no further takes are issued, in-flight ones settle, the transfer fails.
		private Throwable error;
		// A positioned tail take wrote bytes: the partial is hole-riddled and its size no longer reads as a resume prefix.
		private boolean positionedWrites;

		ObjectTransfer(byte[] sha1Hex, Path destination, long fileSize, long offset, IntConsumer progress) {
			this.sha1Hex = sha1Hex;
			this.destination = destination;
			this.fileSize = fileSize;
			this.offset = offset;
			this.progress = progress;
			this.cursor = fileSize;
		}

		CompletableFuture<Path> start() {
			synchronized (transferRegistryLock) {
				activeTransfers.add(this);
			}
			synchronized (lock) {
				pendingItems = 1;
			}
			long headEnd = Math.min(offset + (long) WIRE_CHUNK_BYTES, fileSize) - 1;
			int lane = Math.floorMod(laneCounter.getAndIncrement(), MAX_CONNECTIONS);
			LOGGER.debug("[download] lane {} takes bytes {}..{} of {}", lane, offset, headEnd, objectName());
			submitTake(offset, headEnd, lane);
			pump();
			return future;
		}

		/** A settle anywhere may have freed the credit this dormant transfer waits for: with nothing in flight, no settle of its own will ever re-pump it. */
		void revive() {
			synchronized (lock) {
				if (pendingItems != 0 || error != null || cursor <= floor()) return;
			}
			pump();
		}

		/** Issues tail takes while the window has room; every settle releases its credit and re-pumps. */
		private void pump() {
			while (pacer.tryAcquire()) {
				Take take;
				synchronized (lock) {
					if (error != null || cursor <= floor()) {
						pacer.release(); // nothing left to take
						return;
					}
					take = claimLocked();
				}
				LOGGER.debug("[download] lane {} takes bytes {}..{} of {}", take.lane(), take.start(), take.end(), objectName());
				submitTake(take.start(), take.end(), take.lane());
			}
		}

		private long floor() {
			return offset + (long) WIRE_CHUNK_BYTES;
		}

		private Take claimLocked() {
			long takeEnd = cursor - 1;
			long stealFrom = Math.max(floor(), cursor - (long) WIRE_CHUNK_BYTES);
			cursor = stealFrom;
			pendingItems++;
			return new Take(stealFrom, takeEnd, Math.floorMod(laneCounter.getAndIncrement(), MAX_CONNECTIONS));
		}

		private void submitTake(long takeOffset, long takeEnd, int lane) {
			AtomicLong takeBytes = new AtomicLong(0);
			long takeStart = System.nanoTime();
			// Same call pattern as the app's progress hook: decoded byte counts per read chunk.
			IntConsumer chunkCallback = bytes -> {
				takeBytes.addAndGet(bytes);
				if (progress != null) progress.accept(bytes);
			};
			CompletableFuture<Path> future;
			try {
				// A stale range already surfaces as StaleRangeException from the response parse; no mapping happens here.
				future = withSlot(lane, connection -> connection.sendDownloadFile(sha1Hex, destination, chunkCallback, takeOffset, takeEnd, null, true, -1L));
			} catch (Throwable submitFailure) {
				// The submit never produced a request: settle its credit and tick the barrier down, or the transfer waits for a settle that never comes.
				WireTrace.log("TAKE_FAIL", "object", objectName(), "item", takeOffset + "-" + takeEnd, "error", submitFailure);
				onTakeSettled(takeOffset, 0, System.nanoTime() - takeStart, lane, submitFailure);
				return;
			}
			future.whenComplete((path, takeError) -> onTakeSettled(takeOffset, takeBytes.get(), System.nanoTime() - takeStart, lane, takeError));
		}

		private void onTakeSettled(long takeOffset, long bytes, long nanos, int lane, Throwable takeError) {
			pacer.settle(takeError != null, bytes, nanos, lane);
			boolean done;
			Throwable failure;
			synchronized (lock) {
				if (takeError != null && error == null) error = Throwables.unwrap(takeError);
				if (takeOffset != offset && bytes > 0) positionedWrites = true;
				done = --pendingItems == 0 && (error != null || cursor <= floor());
				failure = error;
			}
			if (done) finish(failure);
			else pump();
			reviveDormant();
		}

		private void finish(Throwable failure) {
			synchronized (transferRegistryLock) {
				activeTransfers.remove(this);
			}
			if (failure != null) {
				if (positionedWrites) {
					// The positioned writes left holes behind the streamed prefix: the partial is worthless for resume.
					deleteQuietly(destination);
				}
				WireTrace.log("DONE", "object", objectName(), "status", "fail:" + Throwables.detail(failure));
				future.completeExceptionally(failure);
				return;
			}
			WireTrace.log("DONE", "object", objectName(), "status", "promote");
			future.complete(destination);
		}

		private String objectName() {
			return destination.getFileName().toString();
		}

		private record Take(long start, long end, int lane) {}
	}

	/** The waiting-track fetch: one identity GET with no negotiation and no resume, aborted past maxBytes. */
	@Override
	public CompletableFuture<Path> downloadSmallObject(byte[] sha1Hex, Path destination, long maxBytes, OutputStream tap) {
		return withSlot(0, connection -> connection.sendDownloadFile(sha1Hex, destination, null, 0L, -1L, tap, false, maxBytes));
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
	public boolean hasWireRoom() {
		return pacer.hasRoom();
	}

	/** Offers freed credits to transfers with nothing in flight; their own settles can never wake them. */
	private void reviveDormant() {
		List<ObjectTransfer> snapshot;
		synchronized (transferRegistryLock) {
			if (activeTransfers.isEmpty()) return;
			snapshot = new ArrayList<>(activeTransfers);
		}
		for (ObjectTransfer transfer : snapshot) transfer.revive();
	}

	/** The one-line window receipt (window path, request duration estimate, per-lane settle rates) a run summary carries. */
	@Override
	public String windowSummary() {
		return pacer.summary();
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
