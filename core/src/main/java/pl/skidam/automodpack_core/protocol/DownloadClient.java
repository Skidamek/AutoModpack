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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
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

	/** One daemon clock for delayed take retries (a throttled host's Retry-After); the wait never parks a lane's reader. */
	private static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "automodpack-retry-clock");
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
	// abortTransfers() advances this before it closes lanes. A take carries the epoch it submitted under; a settle on a
	// stale epoch is "Download aborted" and never retried onto the live pool. A later downloadObject submits under the
	// new epoch and may reopen lanes - the review flow keeps this client across cancel-and-retry.
	private volatile int transferEpoch;
	// The run's honest totals: takes sent (retries included), takes that were retries, and bytes that arrived.
	private final AtomicLong takesSubmitted = new AtomicLong();
	private final AtomicLong takeRetries = new AtomicLong();
	private final AtomicLong bytesDownloaded = new AtomicLong();
	// The receipt's lane count reports the run's real parallelism - the high-water mark of simultaneously open
	// lanes - not the pool's constant, so a run that only ever opened one lane does not receipt five.
	private final AtomicInteger lanesHighWater = new AtomicInteger();
	private final Object poolLock = new Object();
	// The lanes, in creation order: worker i submits to lane i when it has room, so the scheduler's largest-first
	// dispatch puts concurrent big files on distinct lanes while small files fill each lane's window. Small requests
	// are a few hundred bytes of head each, so thousands queue per lane behind one large response - the count and
	// byte tripwires in NetUtils bound the hold; the scheduler dispatches large files to their own workers first, and
	// a non-draining peer trips the 90 s stall window on its lane alone.
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
					client.lanesHighWater.accumulateAndGet(client.lanes.size(), Math::max);
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

	/** Queues a submit carrying {@code debit} window bytes on a lane with room; a new lane opens when all of them are full, past which the waiter waits. */
	private <T> CompletableFuture<T> withSlot(int lane, long debit, Function<Connection, CompletableFuture<T>> operation) {
		return withSlot(lane, debit, operation, transferEpoch);
	}

	private <T> CompletableFuture<T> withSlot(int lane, long debit, Function<Connection, CompletableFuture<T>> operation, int epoch) {
		CompletableFuture<T> future = new CompletableFuture<>();
		synchronized (poolLock) {
			if (closed || epoch != transferEpoch) {
				future.completeExceptionally(deadClientError());
				return future;
			}
			slotWaiters.add(new SlotWaiter<>(lane, debit, operation, future, epoch));
			pumpPool();
		}
		return future;
	}

	private void pumpPool() {
		reapLanes();
		while (!slotWaiters.isEmpty()) {
			SlotWaiter<?> waiter = slotWaiters.peek();
			if (waiter.epoch() != transferEpoch) {
				slotWaiters.remove().future().completeExceptionally(deadClientError());
				continue;
			}
			Connection connection = pick(waiter.lane(), waiter.debit());
			if (connection == null) break;
			slotWaiters.remove().dispatch(connection);
		}
		while (!closed && !slotWaiters.isEmpty() && lanes.size() + openingConnections < LANES) {
			SlotWaiter<?> waiter = slotWaiters.remove();
			if (waiter.epoch() != transferEpoch) {
				waiter.future().completeExceptionally(deadClientError());
				continue;
			}
			openingConnections++;
			openConnectionAsync().whenComplete((connection, error) -> {
				synchronized (poolLock) {
					openingConnections--;
					if (closed || waiter.epoch() != transferEpoch) {
						if (connection != null) closeQuietly(connection);
						waiter.future().completeExceptionally(deadClientError());
					} else if (error != null) {
						WireTrace.log("LANE_FAIL", "lane", waiter.lane(), "error", Throwables.unwrap(error));
						waiter.future().completeExceptionally(Throwables.unwrap(error));
					} else {
						lanes.add(connection);
						lanesHighWater.accumulateAndGet(lanes.size(), Math::max);
						WireTrace.log("LANE_OPEN", "lanes", lanes.size(), "conn", connection.traceId());
						waiter.dispatch(connection);
					}
					pumpPool();
				}
			});
		}
	}

	/** Lane i serves waiter i when it has room for the waiter's debit; otherwise the first lane with room does. Null means every lane is full or gone. */
	private Connection pick(int lane, long debit) {
		if (lanes.isEmpty()) return null;
		if (lane < lanes.size() && lanes.get(lane).hasRoom(debit)) return lanes.get(lane);
		for (Connection connection : lanes) {
			if (connection.hasRoom(debit)) return connection;
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

	private record SlotWaiter<T>(int lane, long debit, Function<Connection, CompletableFuture<T>> operation, CompletableFuture<T> future, int epoch) {
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
	 * One complete object transfer: {@code destination} is the slice directory {@code staging/<sha1>/}. On success
	 * every tile is present; the caller concatenates and hashes. Resume is the directory listing.
	 */
	@Override
	public CompletableFuture<Path> downloadObject(byte[] sha1Hex, Path destination, long fileSize, IntConsumer progress) {
		try {
			Files.createDirectories(destination);
			if (fileSize == 0 || PartialResume.complete(destination, fileSize)) return CompletableFuture.completedFuture(destination);
			return new ObjectTransfer(sha1Hex, destination, fileSize, progress).start();
		} catch (IOException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * One transfer's tiling and completion barrier. Remaining work is the slice directory's missing ranges; the first
	 * take is the lowest gap, further takes steal from the highest. A failed take retries its own range in place a
	 * bounded number of times before failing the transfer. All bookkeeping runs inside the transfer lock, one thread
	 * at a time.
	 */
	final class ObjectTransfer {
		// Survives two consecutive lane deaths (each retry picks a fresh lane via laneCounter); a third failure means the server, not a lane, is gone.
		private static final int MAX_TAKE_ATTEMPTS = 3;
		// The one physical flow-control bound, receipted: a transfer may never hold more unsettled takes than the
		// whole pool's byte window holds of them (5 lanes × 64 MiB / the 4 MiB take = 80), so its takes fill every
		// lane exactly once and further tiles queue as settles free them. Transfers under 80 tiles (~320 MiB) never
		// gate at all, and since the pump only stops early while takes are still outstanding, every transfer owns at
		// least one unsettled take until its range is fully claimed - its own settles always re-pump it, so nothing
		// can go dormant and no revive path is needed.
		private static final int MAX_OUTSTANDING_TAKES = (int) (LANES * NetUtils.PIPELINE_WINDOW_BYTES / WIRE_CHUNK_BYTES);
		// The AIMD floor: one take per lane. Under loss the cap may fall this low, because a refused window costs
		// waiting while an oversized one costs re-downloading everything queued behind a dropped segment.
		private static final int MIN_OUTSTANDING_TAKES = LANES;

		private final byte[] sha1Hex;
		private final Path directory;
		private final long fileSize;
		private final IntConsumer progress;
		private final CompletableFuture<Path> future = new CompletableFuture<>();
		private final Object lock = new Object();
		private final AtomicInteger laneCounter = new AtomicInteger();
		private final ArrayDeque<long[]> remaining;
		private int pendingItems;
		private Throwable error;
		private final boolean openEnded;
		private final int epoch;

		ObjectTransfer(byte[] sha1Hex, Path directory, long fileSize, IntConsumer progress) throws IOException {
			this.sha1Hex = sha1Hex;
			this.directory = directory;
			this.fileSize = fileSize;
			this.progress = progress;
			this.openEnded = rangeIgnoredHost;
			this.epoch = transferEpoch;
			this.remaining = new ArrayDeque<>(PartialResume.remaining(directory, fileSize));
		}

		CompletableFuture<Path> start() {
			if (remaining.isEmpty()) {
				future.complete(directory);
				return future;
			}
			synchronized (lock) {
				pendingItems = 1;
			}
			if (openEnded) {
				submitOpenEnded(remaining.peekFirst()[0]);
				return future;
			}
			long[] head = remaining.pollFirst();
			int lane = Math.floorMod(laneCounter.getAndIncrement(), LANES);
			LOGGER.debug("[download] lane {} takes bytes {}..{} of {}", lane, head[0], head[1], objectName());
			submitTake(new Take(head[0], head[1], lane, 1, 0), new AtomicLong());
			pump();
			return future;
		}

		// The adaptive bound: starts at the ceiling and halves on every take the wire failed, because in-flight
		// exposure that buys re-downloads instead of bytes is exactly what loss punishes; doubles back per
		// window-worth of clean settles. Fast shapes never fail, so they hold the ceiling; a lossy stretch trades
		// window for waste. This is the one idea worth keeping from the deleted WirePacer, stripped of its machinery.
		private int outstandingCap = MAX_OUTSTANDING_TAKES;
		private int cleanSettles;

		/** Issues tail takes while the transfer holds fewer unsettled takes than the adaptive bound allows; every settle re-pumps. */
		private void pump() {
			while (true) {
				Take take;
				synchronized (lock) {
					if (error != null || pendingItems >= outstandingCap || remaining.isEmpty()) return;
					long[] range = remaining.pollLast();
					pendingItems++;
					take = new Take(range[0], range[1], Math.floorMod(laneCounter.getAndIncrement(), LANES), 1, 0);
				}
				LOGGER.debug("[download] lane {} takes bytes {}..{} of {}", take.lane(), take.start(), take.end(), objectName());
				submitTake(take, new AtomicLong());
			}
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
				long waitedNanos = System.nanoTime() - firstByte;
				if (firstByte != 0 && waitedNanos > budgetNanos && fused.compareAndSet(false, true)) {
					WireTrace.log("TAKE_FUSED", "object", objectName(), "item", take.start() + "-" + take.end(), "waited", waitedNanos, "budget", budgetNanos);
					// Named at warn so a field report reads "pathological host", never the lane-died line the closed
					// lane's reader emits right after.
					LOGGER.warn("Take of {} on {} drained {} bytes in {} s, under the {} B/s floor; fusing the lane and letting the retry ladder recover it",
							objectName(), connectionInfo.endpoint.getHostString(), ByteFormat.formatSize(sliceBytes(take)), waitedNanos / 1_000_000_000L, TAKE_RATE_FLOOR_BYTES_PER_SECOND);
					Connection fusedLane = lane.get();
					if (fusedLane != null) NET_EXECUTOR.execute(() -> closeQuietly(fusedLane));
				}
			};
			CompletableFuture<Path> future;
			try {
				long sliceStart = PartialResume.sliceStart(take.start());
				Path slice = PartialResume.sliceFile(directory, sliceStart);
				long writeAt = take.start() - sliceStart;
				long debit = ObjectTake.debit(take.start(), take.end());
				future = withSlot(take.lane(), debit, connection -> {
					lane.set(connection);
					return connection.sendDownloadFile(sha1Hex, ObjectTake.rangedSlice(slice, chunkCallback, take.start(), take.end(), fileSize, writeAt));
				}, epoch);
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

		/**
		 * The wait before one throttled retry: the provider's Retry-After when it sent one (already clamped to the
		 * network timeout by the wire), else a bounded default with jitter so a stampede of clients does not retry in
		 * lockstep. Plain failures - lane deaths, timeouts - retry immediately as always.
		 */
		static long retryDelayMillis(Throwable takeError) {
			if (!(takeError instanceof HostThrottleException throttled)) return 0;
			if (throttled.retryAfterMillis() > 0) return throttled.retryAfterMillis();
			return 1000 + ThreadLocalRandom.current().nextLong(1000);
		}

		/**
		 * The trickle fuse budget: a slice must drain within the {@link NetUtils#TAKE_RATE_FLOOR_BYTES_PER_SECOND}
		 * rate (a 4 MiB slice gets ~1024 s, its congested-share drain at 6.25 KiB/s needs ~655 s), but never inside
		 * the 90 s write-stall window.
		 */
		static long takeBudgetNanos(long sliceBytes) {
			if (sliceBytes > Long.MAX_VALUE / 1_000_000_000L) return Long.MAX_VALUE; // the honest budget for a >8.6 GiB slice saturates instead of overflowing negative
			return Math.max(TRANSFER_WRITE_STALL_TIMEOUT.toNanos(), sliceBytes * 1_000_000_000L / TAKE_RATE_FLOOR_BYTES_PER_SECOND);
		}

		private void onTakeSettled(Take take, AtomicLong takeBytes, Throwable takeError) {
			if (takeError instanceof RangeIgnoredException) markRangeIgnoredHost();
			boolean retried = takeError != null && take.attempt() < MAX_TAKE_ATTEMPTS && retryWorth(takeError);
			if (retried) {
				takeRetries.incrementAndGet();
				// The barrier stays charged: the retried range is the same one unsettled unit of work. A throttled
				// answer waits out its window on the retry clock instead of an immediate re-issue - the wait never
				// parks a lane's reader, and an abort while waiting settles the take as cancelled, not restarted.
				long delayMillis = retryDelayMillis(takeError);
				Runnable retry = () -> {
					if (epoch != transferEpoch) {
						onTakeSettled(take, takeBytes, new IOException("Download aborted"));
						return;
					}
					try {
						if (take.end() < 0) {
							submitOpenEnded(PartialResume.nextByte(directory, fileSize));
							return;
						}
						Take next = retryTake(take, takeBytes.get());
						if (next.start() > next.end()) onTakeSettled(take, takeBytes, null);
						else submitTake(next, takeBytes);
					} catch (IOException e) {
						onTakeSettled(take, takeBytes, e);
					}
				};
				if (delayMillis > 0) RETRY_SCHEDULER.schedule(retry, delayMillis, TimeUnit.MILLISECONDS);
				else retry.run();
				WireTrace.log("TAKE_RETRY", "object", objectName(), "item", take.start() + "-" + take.end(), "attempt", take.attempt(), "delay", delayMillis + "ms", "error", takeError);
				return;
			}
			// The take's whole life is over: its cumulative count is the honest number of bytes its range put on the wire.
			bytesDownloaded.addAndGet(takeBytes.get());
			boolean done;
			Throwable failure;
			synchronized (lock) {
				if (retried) {
					// The wire failed this take: halve the in-flight bound and forget the clean streak. Lost or
					// reset bytes mean the unsettled queue was buying re-downloads, not progress.
					if (outstandingCap > MIN_OUTSTANDING_TAKES) {
						outstandingCap = Math.max(MIN_OUTSTANDING_TAKES, outstandingCap / 2);
						WireTrace.log("TAKE_WINDOW_DOWN", "object", objectName(), "cap", outstandingCap);
					}
					cleanSettles = 0;
				} else if (takeError == null && ++cleanSettles >= Math.max(MIN_OUTSTANDING_TAKES, outstandingCap)) {
					cleanSettles = 0;
					if (outstandingCap < MAX_OUTSTANDING_TAKES) {
						outstandingCap = Math.min(MAX_OUTSTANDING_TAKES, outstandingCap * 2);
						WireTrace.log("TAKE_WINDOW_UP", "object", objectName(), "cap", outstandingCap);
					}
				}
				if (takeError != null && error == null) error = Throwables.unwrap(takeError);
				done = --pendingItems == 0 && (error != null || remaining.isEmpty() || openEnded);
				failure = error;
			}
			if (done) finish(failure);
			else pump();
		}

		private Take retryTake(Take take, long progressBase) throws IOException {
			long sliceStart = PartialResume.sliceStart(take.start());
			long expected = PartialResume.sliceLength(sliceStart, fileSize);
			long present = PartialResume.presentLength(PartialResume.sliceFile(directory, sliceStart), expected);
			if (present >= expected) return new Take(take.end() + 1, take.end(), take.lane(), take.attempt() + 1, progressBase);
			return new Take(sliceStart + present, sliceStart + expected - 1, Math.floorMod(laneCounter.getAndIncrement(), LANES), take.attempt() + 1, progressBase);
		}

		private void submitOpenEnded(long start) {
			takesSubmitted.incrementAndGet();
			OutputStream writer;
			try {
				writer = PartialResume.writer(directory, fileSize, start);
			} catch (IOException e) {
				onTakeSettled(new Take(start, -1, 0, 1, 0), new AtomicLong(), e);
				return;
			}
			AtomicLong takeBytes = new AtomicLong();
			IntConsumer chunkCallback = bytes -> {
				takeBytes.addAndGet(bytes);
				if (progress != null) progress.accept(bytes);
			};
			Take take = new Take(start, -1, 0, 1, 0);
			withSlot(0, WIRE_CHUNK_BYTES, connection -> connection.sendDownloadFile(sha1Hex, ObjectTake.openEnded(writer, chunkCallback, start, fileSize)), epoch)
					.whenComplete((path, takeError) -> {
						try {
							writer.close();
						} catch (IOException ignored) {
						}
						onTakeSettled(take, takeBytes, takeError);
					});
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
			if (error instanceof MissingObjectException || error instanceof UnauthorizedException || error instanceof StaleRangeException || error instanceof LocalStorageException
					|| error instanceof RangeIgnoredException)
				return true;
			return error instanceof IOException && error.getMessage() != null && error.getMessage().contains("does not match the expected object size");
		}

		private boolean retryWorth(Throwable error) {
			return !permanentFailure(error) && epoch == transferEpoch;
		}

		private void finish(Throwable failure) {
			if (failure != null) {
				WireTrace.log("DONE", "object", objectName(), "status", "fail:" + Throwables.detail(failure));
				future.completeExceptionally(failure);
				return;
			}
			WireTrace.log("DONE", "object", objectName(), "status", "promote");
			future.complete(directory);
		}

		private String objectName() {
			return directory.getFileName().toString();
		}

		private record Take(long start, long end, int lane, int attempt, long progressBase) {}
	}

	/** The waiting-track fetch: one identity whole-object take with no negotiation and no resume, aborted past maxBytes. */
	@Override
	public CompletableFuture<Path> downloadSmallObject(byte[] sha1Hex, Path destination, long maxBytes, OutputStream tap) {
		return withSlot(0, WIRE_CHUNK_BYTES, connection -> connection.sendDownloadFile(sha1Hex, ObjectTake.wholeObject(destination, tap, maxBytes)));
	}

	/** Document fetch (reserved keys) under the conditional, null for unconditional: a matching validator answers 304 and {@code destination} is not written. */
	@Override
	public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, DocumentConditional conditional, IntConsumer chunkCallback) {
		return withSlot(0, WIRE_CHUNK_BYTES, connection -> connection.sendDownloadDocument(key, destination, conditional, chunkCallback, null));
	}

	/** The same fetch with a tap: served body bytes reach the tap (decode-while-downloading) and the destination alike. */
	@Override
	public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, DocumentConditional conditional, OutputStream tap) {
		return withSlot(0, WIRE_CHUNK_BYTES, connection -> connection.sendDownloadDocument(key, destination, conditional, null, tap));
	}

	/** The error a submit is refused with once the client is closed or its submit's epoch is stale; a stale epoch names the abort, every other death reads as closed. */
	private IOException deadClientError() {
		return new IOException(closed ? "Download client is closed" : "Download aborted");
	}

	/** Drops every pooled and in-flight transfer connection so a cancelled run cannot poison the next one. */
	@Override
	public void abortTransfers() {
		// Epoch advances before the lanes close: a take that fails on the close must already see a stale epoch, or
		// its retry slips past this method onto the freshly reopened pool and completes a transfer nobody wants.
		List<Connection> connections;
		List<SlotWaiter<?>> waiters;
		synchronized (poolLock) {
			if (closed) return;
			transferEpoch++;
			connections = new ArrayList<>(lanes);
			waiters = new ArrayList<>(slotWaiters);
			lanes.clear();
			slotWaiters.clear();
		}
		connections.forEach(NetUtils::closeQuietly);
		// Queued waiters never reach a lane. Later submits carry the new epoch and may reopen lanes for the next run.
		IOException aborted = new IOException("Download aborted");
		waiters.forEach(waiter -> waiter.future().completeExceptionally(aborted));
	}

	/** The one-line receipt a run summary carries: takes sent, retries and bytes arrived, over the lanes the run actually opened. */
	@Override
	public String windowSummary() {
		int lanes = lanesHighWater.get();
		return takesSubmitted.get() + " takes (" + takeRetries.get() + " retried), " + ByteFormat.formatSize(bytesDownloaded.get()) + " over " + lanes + (lanes == 1 ? " lane" : " lanes");
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
		connections.forEach(NetUtils::closeQuietly);
	}
}
