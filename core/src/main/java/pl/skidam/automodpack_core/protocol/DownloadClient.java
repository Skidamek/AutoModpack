package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntConsumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.protocol.Connection.ObjectTake;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.ByteFormat;
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

	// The pooled pipeline lanes. They buy exactly two physical things: loss-regime multiplication (a lost segment stalls
	// one lane's pipeline, not the transfer) and streaming through a lane re-handshake; on a clean link a single
	// connection is already bandwidth-equivalent. Measured on the bench fixture (101 files, ~250 MiB): clean and
	// 0.1%-loss runs are indistinguishable across {1,3,5} lanes, while at 1% loss + 100 ms delay the same transfer
	// takes 230 s at 5 lanes, 442 s at 3, and 910 s at 1 - the stall regime scales near-linearly with lanes, so the
	// count stays 5. Kept split from the platform-worker count it used to be welded to.
	public static final int LANES = 5;

	private final ConnectionJsons.ConnectionInfo connectionInfo;
	private final String secret;
	private final Function<X509Certificate, CompletableFuture<Boolean>> trustCallback;
	private final Duration preConfigurationKeepaliveInterval;
	private final CustomizableTrustManager.SessionTrust sessionTrust;
	private final CustomizableTrustManager trustManager;
	private final SSLContext sslContext;
	private final TransportRoute route;
	// Set once a take's response head proves the peer ignores Range (a 200 past the requested slice): every whole-object
	// task on this client then takes the object open-ended instead of tiling. One client is one endpoint - all lanes
	// terminate on the same peer and redirects drop authority - so the capability verdict is per client, not per file.
	volatile boolean rangeIgnoredHost;
	// Set by abortTransfers before its lanes close: a take failing on the closed lanes must not retry onto the pool
	// it was aborted against. The client is never reused for transfers after an abort (every run gets a fresh client
	// from ManifestFetcher), so one flag covers every transfer.
	private volatile boolean aborted;
	// The run's honest totals: takes sent (retries included), takes that were retries, and bytes that arrived.
	private final AtomicLong takesSubmitted = new AtomicLong();
	private final AtomicLong takeRetries = new AtomicLong();
	private final AtomicLong bytesDownloaded = new AtomicLong();
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

	private record TlsCandidate(SSLSocket socket, Socket transport) {}

	private DownloadClient(ConnectionJsons.ConnectionInfo connectionInfo, String secret, Function<X509Certificate, CompletableFuture<Boolean>> trustCallback,
			Duration preConfigurationKeepaliveInterval, TransportRoute route) {
		this.connectionInfo = connectionInfo;
		this.secret = secret;
		this.trustCallback = trustCallback;
		this.preConfigurationKeepaliveInterval = preConfigurationKeepaliveInterval;
		this.route = route;
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
		return CandidateTrustValidation.validate(new CandidateTrustValidation.Candidate(candidate.socket(), trustManager, sessionTrust, connectionInfo.origin.getHostString(),
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
		while (!closed && !slotWaiters.isEmpty() && lanes.size() + openingConnections < LANES) {
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
		List<Connection> dead = null;
		for (Connection connection : lanes) {
			if (connection.isActive()) continue;
			if (dead == null) dead = new ArrayList<>();
			dead.add(connection);
		}
		if (dead == null) return;
		// Removed before closing: closing a lane fails its pending takes synchronously, which can re-enter the pool on
		// the same thread, and a nested reap must see a structurally consistent list, not an iterator mid-removal.
		lanes.removeAll(dead);
		for (Connection connection : dead) {
			WireTrace.log("LANE_REAP", "conn", connection.traceId(), "lanes", lanes.size());
			closeQuietly(connection);
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
	 * zero-size shortcut run on the calling thread exactly as the old manager's dispatch did.
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
			return new ObjectTransfer(sha1Hex, destination, fileSize, offset, progress).start();
		} catch (IOException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** The byte offset the transfer resumes from: the destination's size while it is a valid prefix, else a fresh start. */
	private static long resumeOffset(Path destination, long fileSize) {
		return PartialResume.offset(destination, fileSize);
	}

	/**
	 * One transfer's tiling and completion barrier. The first take covers the streamer's head chunk; every further
	 * take claims the uncovered tail. A failed take retries its own range in place a bounded number of times before
	 * failing the transfer. All bookkeeping runs inside the transfer lock, one thread at a time.
	 */
	final class ObjectTransfer {
		// Survives two consecutive lane deaths (each retry picks a fresh lane via laneCounter); a third failure means the server, not a lane, is gone.
		private static final int MAX_TAKE_ATTEMPTS = 3;
		// The one physical flow-control bound, receipted: a transfer may never hold more unsettled takes than the
		// whole pool has pipeline slots (5 lanes × depth 8 = 40), so its takes fill every lane exactly once and
		// further tiles queue as settles free them. Transfers under 40 tiles (~160 MiB) never gate at all, and since
		// the pump only stops early while takes are still outstanding, every transfer owns at least one unsettled
		// take until its range is fully claimed - its own settles always re-pump it, so nothing can go dormant and
		// no revive path is needed.
		private static final int MAX_OUTSTANDING_TAKES = LANES * Connection.PIPELINE_DEPTH;

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
		// A range-ignoring host serves every bounded take as a full 200, so tiling would only re-detect the same verdict
		// per slice: one open-ended take covers the object, and the cursor stays at the resume offset so no tail is claimed.
		private final boolean openEnded;

		ObjectTransfer(byte[] sha1Hex, Path destination, long fileSize, long offset, IntConsumer progress) {
			this.sha1Hex = sha1Hex;
			this.destination = destination;
			this.fileSize = fileSize;
			this.offset = offset;
			this.progress = progress;
			this.openEnded = rangeIgnoredHost;
			this.cursor = openEnded ? offset : fileSize;
		}

		CompletableFuture<Path> start() {
			synchronized (lock) {
				pendingItems = 1;
			}
			long headEnd = openEnded ? -1 : Math.min(offset + (long) WIRE_CHUNK_BYTES, fileSize) - 1;
			int lane = Math.floorMod(laneCounter.getAndIncrement(), LANES);
			LOGGER.debug("[download] lane {} takes bytes {}..{} of {}", lane, offset, headEnd, objectName());
			submitTake(new Take(offset, headEnd, lane, 1, 0), new AtomicLong());
			if (!openEnded) pump();
			return future;
		}

		/** Issues tail takes while the transfer holds fewer unsettled takes than the pool's pipeline slots; every settle re-pumps. */
		private void pump() {
			while (true) {
				Take take;
				synchronized (lock) {
					if (error != null || pendingItems >= MAX_OUTSTANDING_TAKES || cursor <= floor()) return;
					take = claimLocked();
				}
				LOGGER.debug("[download] lane {} takes bytes {}..{} of {}", take.lane(), take.start(), take.end(), objectName());
				submitTake(take, new AtomicLong());
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
			return new Take(stealFrom, takeEnd, Math.floorMod(laneCounter.getAndIncrement(), LANES), 1, 0);
		}

		private void submitTake(Take take, AtomicLong takeBytes) {
			takesSubmitted.incrementAndGet();
			long budgetNanos = takeBudgetNanos(sliceBytes(take));
			AtomicReference<Connection> lane = new AtomicReference<>();
			AtomicBoolean fused = new AtomicBoolean();
			// Same call pattern as the app's progress hook: decoded byte counts per read chunk, reported only past the
			// retried attempt's watermark so already-counted bytes never re-fire. Past the rate-floor budget the lane is
			// closed asynchronously - never synchronously from the reader's own callback stack - and the retry ladder
			// recovers the take; a persistently trickling host burns its attempts and fails the transfer loudly.
			AtomicLong firstByteNanos = new AtomicLong();
			IntConsumer chunkCallback = bytes -> {
				firstByteNanos.compareAndSet(0, System.nanoTime());
				long cumulative = takeBytes.addAndGet(bytes);
				long counted = Math.min(bytes, Math.max(0, cumulative - take.progressBase()));
				if (progress != null && counted > 0) progress.accept((int) counted);
				long firstByte = firstByteNanos.get();
				if (firstByte != 0 && System.nanoTime() - firstByte > budgetNanos && fused.compareAndSet(false, true) && lane.get() != null) NET_EXECUTOR.execute(() -> closeQuietly(lane.get()));
			};
			CompletableFuture<Path> future;
			try {
				// A stale range already surfaces as StaleRangeException from the response parse; no mapping happens here.
				future = withSlot(take.lane(), connection -> {
					lane.set(connection);
					return connection.sendDownloadFile(sha1Hex, ObjectTake.rangedSlice(destination, chunkCallback, take.start(), take.end(), fileSize));
				});
			} catch (Throwable submitFailure) {
				// The submit never produced a request: the settle path retries or books it like any other failure.
				WireTrace.log("TAKE_FAIL", "object", objectName(), "item", take.start() + "-" + take.end(), "error", submitFailure);
				onTakeSettled(take, takeBytes, submitFailure);
				return;
			}
			future.whenComplete((path, takeError) -> onTakeSettled(take, takeBytes, takeError));
		}

		/** The take's slice size: a bounded take's exact range, or the open-ended tail behind its start. */
		private long sliceBytes(Take take) {
			return take.end() >= 0 ? take.end() - take.start() + 1 : fileSize - take.start();
		}

		/** The trickle fuse budget: a slice must drain within the rate floor, but never inside the write-stall window. */
		static long takeBudgetNanos(long sliceBytes) {
			if (sliceBytes > Long.MAX_VALUE / 1_000_000_000L) return Long.MAX_VALUE; // the honest budget for a >8.6 GiB slice saturates instead of overflowing negative
			return Math.max(TRANSFER_WRITE_STALL_TIMEOUT.toNanos(), sliceBytes * 1_000_000_000L / TAKE_RATE_FLOOR_BYTES_PER_SECOND);
		}

		private void onTakeSettled(Take take, AtomicLong takeBytes, Throwable takeError) {
			if (takeError instanceof RangeIgnoredException) markRangeIgnoredHost();
			if (takeError != null && take.attempt() < MAX_TAKE_ATTEMPTS && retryWorth(takeError)) {
				takeRetries.incrementAndGet();
				WireTrace.log("TAKE_RETRY", "object", objectName(), "item", take.start() + "-" + take.end(), "attempt", take.attempt(), "error", takeError);
				// The barrier stays charged: the retried range is the same one unsettled unit of work.
				submitTake(new Take(take.start(), take.end(), Math.floorMod(laneCounter.getAndIncrement(), LANES), take.attempt() + 1, takeBytes.get()), takeBytes);
				return;
			}
			// The take's whole life is over: its cumulative count is the honest number of bytes its range put on the wire.
			bytesDownloaded.addAndGet(takeBytes.get());
			boolean done;
			Throwable failure;
			synchronized (lock) {
				if (takeError != null && error == null) error = Throwables.unwrap(takeError);
				if (take.start() != offset && takeBytes.get() > 0) positionedWrites = true;
				done = --pendingItems == 0 && (error != null || cursor <= floor());
				failure = error;
			}
			if (done) finish(failure);
			else pump();
		}

		/** Marks the whole client degraded once, with the one loud line naming the host every lane of this client terminates on. */
		private void markRangeIgnoredHost() {
			synchronized (poolLock) {
				if (rangeIgnoredHost) return;
				rangeIgnoredHost = true;
			}
			LOGGER.warn("Host {} ignores HTTP Range requests; every object on this client now downloads in one open-ended take", connectionInfo.endpoint.getHostString());
		}

		/** Pack-hygiene verdicts are final for the range; anything else (a lost lane, a timeout) is worth another attempt. */
		private static boolean permanentFailure(Throwable error) {
			return error instanceof MissingObjectException || error instanceof UnauthorizedException || error instanceof StaleRangeException || error instanceof LocalStorageException
					|| error instanceof RangeIgnoredException;
		}

		private boolean retryWorth(Throwable error) {
			return !permanentFailure(error) && !aborted;
		}

		private void finish(Throwable failure) {
			if (failure != null) {
				if (positionedWrites) {
					// The positioned writes left holes behind the streamed prefix: the partial is worthless for resume.
					PartialResume.deleteQuietly(destination);
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

		private record Take(long start, long end, int lane, int attempt, long progressBase) {}
	}

	/** The waiting-track fetch: one identity whole-object take with no negotiation and no resume, aborted past maxBytes. */
	@Override
	public CompletableFuture<Path> downloadSmallObject(byte[] sha1Hex, Path destination, long maxBytes, OutputStream tap) {
		return withSlot(0, connection -> connection.sendDownloadFile(sha1Hex, ObjectTake.wholeObject(destination, tap, maxBytes)));
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
		// Flagged before the lanes close: a take that fails on the close must already see the client as aborted, or
		// its retry slips past this method onto the freshly reopened pool and completes a transfer nobody wants.
		aborted = true;
		List<Connection> connections;
		List<SlotWaiter<?>> waiters;
		synchronized (poolLock) {
			if (closed) return;
			connections = new ArrayList<>(lanes);
			waiters = new ArrayList<>(slotWaiters);
			lanes.clear();
			slotWaiters.clear();
		}
		connections.forEach(DownloadClient::closeQuietly);
		// Queued waiters never reach a lane: a cancelled run sends no requests, so no pump reopens connections for them.
		IOException aborted = new IOException("Download aborted");
		waiters.forEach(waiter -> waiter.future().completeExceptionally(aborted));
	}

	/** The one-line receipt a run summary carries: takes sent, retries and bytes arrived, over the pool's lanes. */
	@Override
	public String windowSummary() {
		return takesSubmitted.get() + " takes (" + takeRetries.get() + " retried), " + ByteFormat.formatSize(bytesDownloaded.get()) + " over " + LANES + " lanes";
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
