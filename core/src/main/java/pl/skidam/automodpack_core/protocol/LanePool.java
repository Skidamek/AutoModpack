package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.DownloadClient.LANES;
import static pl.skidam.automodpack_core.protocol.DownloadClient.LANE_OPEN_COOLDOWN_BASE_MILLIS;
import static pl.skidam.automodpack_core.protocol.DownloadClient.LANE_OPEN_COOLDOWN_CAP_MILLIS;
import static pl.skidam.automodpack_core.protocol.DownloadClient.RETRY_SCHEDULER;
import static pl.skidam.automodpack_core.protocol.NetUtils.closeQuietly;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import pl.skidam.automodpack_core.utils.Throwables;

/**
 * The pooled lanes of one endpoint: which requests wait for room, which lane serves them, when a dead lane is reaped, and
 * the backoff after a refused connect. Everything it owns is per endpoint - one client is one endpoint, so a proxy that
 * refused seconds ago likely still refuses and a player who said no meant it; both survive abortTransfers() epoch bumps
 * and close(), and the armed flag keeps at most one cooldown re-pump pending on the retry clock.
 */
final class LanePool {

	private final TransportSession session;
	private final LaneConnector connector;
	private final String secret;
	private final String hostHeader;
	// The lanes, in creation order: worker i submits to lane i when it has room, so the scheduler's largest-first
	// dispatch puts concurrent big files on distinct lanes while small files fill each lane's window. Small requests are a
	// few hundred bytes of head each, so thousands queue per lane behind one large response - the count and byte tripwires
	// in NetUtils bound the hold; the scheduler dispatches large files to their own workers first, and a non-draining peer
	// trips the 90 s stall window on its lane alone.
	private final List<Connection> lanes = new ArrayList<>();
	private final Deque<SlotWaiter<?>> slotWaiters = new ArrayDeque<>();
	private final Object lock = new Object();
	private int openingConnections;
	private int failedLaneOpens;
	private long laneOpenCooldownUntilNanos;
	private boolean laneCooldownRePumpArmed;
	/** The sticky trust verdict, kept as the exception itself so the state and the error handed to every waiter are one value. */
	private UserRejectedCertificateException trustRejection;
	// abortTransfers() advances this before it closes lanes. A take carries the epoch it submitted under; a settle on a
	// stale epoch is "Download aborted" and never retried onto the live pool. A later submit carries the new epoch and may
	// reopen lanes - the review flow keeps one client across cancel-and-retry.
	private volatile int transferEpoch;
	private volatile boolean closed;

	LanePool(TransportSession session, LaneConnector connector, String secret, String hostHeader) {
		this.session = session;
		this.connector = connector;
		this.secret = secret;
		this.hostHeader = hostHeader;
	}

	/** The epoch every submit of this moment belongs to; a transfer captures it once and re-reads it per retry. */
	int epoch() {
		return transferEpoch;
	}

	/** Opens one lane and puts it in the pool; the first lane of a fresh client comes through here. */
	CompletableFuture<Connection> openLane() {
		return connector.open(secret, hostHeader, this::slotFreed).thenApply(connection -> {
			synchronized (lock) {
				lanes.add(connection);
				session.lanesHighWater.accumulateAndGet(lanes.size(), Math::max);
			}
			return connection;
		});
	}

	/** Queues a submit carrying {@code debit} window bytes on a lane with room; a new lane opens when all of them are full, past which the waiter waits. */
	<T> CompletableFuture<T> withSlot(int lane, long debit, Function<Connection, CompletableFuture<T>> operation) {
		return withSlot(lane, debit, operation, transferEpoch);
	}

	<T> CompletableFuture<T> withSlot(int lane, long debit, Function<Connection, CompletableFuture<T>> operation, int epoch) {
		CompletableFuture<T> future = new CompletableFuture<>();
		synchronized (lock) {
			if (closed || epoch != transferEpoch) {
				future.completeExceptionally(deadClientError());
				return future;
			}
			slotWaiters.add(new SlotWaiter<>(lane, debit, operation, future, epoch));
			pump();
		}
		return future;
	}

	private void pump() {
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
			if (trustRejection != null) {
				// The player distrusted this endpoint: no open ever again. Live lanes keep serving through pick(); once
				// nothing alive can serve the queue, failing it loudly is the feature - a queued waiter would hang forever.
				if (lanes.isEmpty()) failQueuedWaiters(trustRejection);
				break;
			}
			long cooldownRemainingNanos = laneOpenCooldownUntilNanos - System.nanoTime();
			if (cooldownRemainingNanos > 0) {
				// Backed off: waiters stay queued (a queued waiter burns nothing - a take only spends an attempt when it
				// settles with an error) and one re-pump is armed for the remaining delay.
				armLaneCooldownRePump(cooldownRemainingNanos);
				break;
			}
			SlotWaiter<?> waiter = slotWaiters.remove();
			if (waiter.epoch() != transferEpoch) {
				waiter.future().completeExceptionally(deadClientError());
				continue;
			}
			openingConnections++;
			connector.open(secret, hostHeader, this::slotFreed).whenComplete((connection, error) -> {
				synchronized (lock) {
					openingConnections--;
					if (closed || waiter.epoch() != transferEpoch) {
						if (connection != null) closeQuietly(connection);
						waiter.future().completeExceptionally(deadClientError());
					} else if (error != null) {
						onLaneOpenFailed(waiter, Throwables.unwrap(error));
					} else {
						// The cooldown is definitionally expired when an open proceeds, so the streak restarts here.
						failedLaneOpens = 0;
						lanes.add(connection);
						session.lanesHighWater.accumulateAndGet(lanes.size(), Math::max);
						WireTrace.log("LANE_OPEN", "lanes", lanes.size(), "conn", connection.traceId());
						waiter.dispatch(connection);
					}
					pump();
				}
			});
		}
	}

	/** A lane open died: the player's explicit distrust ends every future open on this client, anything else buys a jittered exponential cooldown before the next probe. Called under the lock. */
	private void onLaneOpenFailed(SlotWaiter<?> waiter, Throwable failure) {
		if (failure instanceof UserRejectedCertificateException rejection) {
			if (trustRejection == null) {
				trustRejection = rejection;
				LOGGER.error(
						"Host {} failed to open a download connection because the certificate was rejected, so this client will never connect to it again and every pending transfer on it fails; accepting the server's certificate is the only way back",
						session.endpointHost);
			}
			WireTrace.log("LANE_FAIL", "lane", waiter.lane(), "error", failure);
			waiter.future().completeExceptionally(rejection);
			failQueuedWaiters(trustRejection);
			return;
		}
		// The streak counts time-separated failures, not opens: the siblings of one blip (up to LANES of them are in
		// flight) all land inside the cooldown this first one just bought, so they neither deepen it nor skip past it.
		if (laneOpenCooldownUntilNanos <= System.nanoTime()) failedLaneOpens++;
		long delayMillis = laneOpenCooldownMillis();
		laneOpenCooldownUntilNanos = Math.max(laneOpenCooldownUntilNanos, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis));
		WireTrace.log("LANE_FAIL", "lane", waiter.lane(), "error", failure, "retryIn", delayMillis + "ms");
		waiter.future().completeExceptionally(failure);
		// The rest of the queue keeps waiting instead of being drained now: this cooldown is the ladder, and a queued waiter
		// burns nothing until it settles with an error, so the take ladder carries the failure to the player on its own
		// attempt budget. Draining here too would make the ladder pointless exactly when it is needed.
	}

	/**
	 * The next lane-open probe's wait: exponential in the consecutive failures, jittered in [0.5, 1.5)x so refused
	 * endpoints are not re-probed in lockstep, and capped after the jitter so the cap above is the real one.
	 */
	private long laneOpenCooldownMillis() {
		long delay = LANE_OPEN_COOLDOWN_BASE_MILLIS << Math.min(failedLaneOpens - 1, 30);
		return Math.min(LANE_OPEN_COOLDOWN_CAP_MILLIS, (long) (delay * ThreadLocalRandom.current().nextDouble(0.5, 1.5)));
	}

	/** Schedules the one pending pump for when the lane-open cooldown expires; the pump itself re-arms if the deadline has since moved further out. */
	private void armLaneCooldownRePump(long remainingNanos) {
		if (laneCooldownRePumpArmed) return;
		laneCooldownRePumpArmed = true;
		RETRY_SCHEDULER.schedule(() -> {
			synchronized (lock) {
				laneCooldownRePumpArmed = false;
				if (!closed) pump();
			}
		}, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)), TimeUnit.MILLISECONDS);
	}

	/** Fails every queued waiter with the pool-level error of the moment; a stale epoch still reads as the abort it is. */
	private void failQueuedWaiters(Throwable error) {
		SlotWaiter<?> queued;
		while ((queued = slotWaiters.poll()) != null) {
			queued.future().completeExceptionally(queued.epoch() != transferEpoch ? deadClientError() : error);
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
		synchronized (lock) {
			if (!closed) pump();
		}
	}

	/** The error a submit is refused with once the pool is closed or its submit's epoch is stale; a stale epoch names the abort, every other death reads as closed. */
	private IOException deadClientError() {
		return new IOException(closed ? "Download client is closed" : "Download aborted");
	}

	/** Drops every pooled and in-flight transfer connection so a cancelled run cannot poison the next one. */
	void abortTransfers() {
		// The epoch advances before the lanes close: a take that fails on the close must already see a stale epoch, or
		// its retry slips past this method onto the freshly reopened pool and completes a transfer nobody wants.
		List<Connection> connections;
		List<SlotWaiter<?>> waiters;
		synchronized (lock) {
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

	/** Closes the pool for good: nothing submits onto it and nothing reopens. */
	void close() {
		List<Connection> connections;
		List<SlotWaiter<?>> waiters;
		synchronized (lock) {
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
}
