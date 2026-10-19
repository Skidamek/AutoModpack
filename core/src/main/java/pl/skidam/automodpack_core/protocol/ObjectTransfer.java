package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.DownloadClient.LANES;
import static pl.skidam.automodpack_core.protocol.DownloadClient.RETRY_SCHEDULER;
import static pl.skidam.automodpack_core.protocol.NetUtils.TAKE_RATE_FLOOR_BYTES_PER_SECOND;
import static pl.skidam.automodpack_core.protocol.NetUtils.TRANSFER_WRITE_STALL_TIMEOUT;
import static pl.skidam.automodpack_core.protocol.NetUtils.WIRE_CHUNK_BYTES;
import static pl.skidam.automodpack_core.protocol.NetUtils.closeQuietly;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import pl.skidam.automodpack_core.protocol.Connection.ObjectTake;
import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.RateFuse;
import pl.skidam.automodpack_core.utils.Throwables;

/**
 * One complete object transfer: {@code destination} is the slice directory {@code staging/<sha1>/}. On success every tile
 * is present; the caller concatenates and hashes. Resume is the directory listing. Remaining work is that directory's
 * missing ranges; the first take is the lowest gap, further takes steal from the highest. A failed take retries its own
 * range in place a bounded number of times before failing the transfer. All bookkeeping runs inside the transfer lock,
 * one thread at a time.
 */
final class ObjectTransfer {

	// Survives two consecutive lane deaths (each retry picks a fresh lane via laneCounter); a third failure means the server, not a lane, is gone.
	private static final int MAX_TAKE_ATTEMPTS = 3;
	// The one physical flow-control bound, receipted: a transfer may never hold more unsettled takes than the
	// whole pool's byte window holds of them (5 lanes × 16 MiB / the 4 MiB take = 20, per the loss bench that
	// cut the lane window from 64 MiB), so its takes fill every lane exactly once and further tiles queue as
	// settles free them. Transfers under 20 tiles (~80 MiB) never gate at all, and since the pump only stops
	// early while takes are still outstanding, every transfer owns at least one unsettled take until its range
	// is fully claimed - its own settles always re-pump it, so nothing can go dormant and no revive path is needed.
	private static final int MAX_OUTSTANDING_TAKES = (int) (LANES * NetUtils.PIPELINE_WINDOW_BYTES / WIRE_CHUNK_BYTES);
	// The AIMD floor: one take per lane. Under loss the cap may fall this low, because a refused window costs
	// waiting while an oversized one costs re-downloading everything queued behind a dropped segment.
	private static final int MIN_OUTSTANDING_TAKES = LANES;

	private final byte[] sha1Hex;
	private final Path directory;
	private final long fileSize;
	private final IntConsumer progress;
	private final LanePool lanes;
	private final IntSupplier epoch;
	private final TransportSession session;
	private final CompletableFuture<Path> future = new CompletableFuture<>();
	private final Object lock = new Object();
	private final AtomicLong laneCounter = new AtomicLong();
	private final ArrayDeque<long[]> remaining;
	private int pendingItems;
	private Throwable error;
	private final boolean openEnded;
	private final int submittedEpoch;

	ObjectTransfer(byte[] sha1Hex, Path directory, long fileSize, IntConsumer progress, LanePool lanes, IntSupplier epoch, TransportSession session) throws IOException {
		this.sha1Hex = sha1Hex;
		this.directory = directory;
		this.fileSize = fileSize;
		this.progress = progress;
		this.lanes = lanes;
		this.epoch = epoch;
		this.session = session;
		this.openEnded = session.rangeIgnoredHost();
		this.submittedEpoch = epoch.getAsInt();
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
		int lane = laneIndex();
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
				take = new Take(range[0], range[1], laneIndex(), 1, 0);
			}
			LOGGER.debug("[download] lane {} takes bytes {}..{} of {}", take.lane(), take.start(), take.end(), objectName());
			submitTake(take, new AtomicLong());
		}
	}

	private int laneIndex() {
		return Math.floorMod(laneCounter.getAndIncrement(), LANES);
	}

	private void submitTake(Take take, AtomicLong takeBytes) {
		session.takesSubmitted.incrementAndGet();
		AtomicReference<Connection> lane = new AtomicReference<>();
		// Same call pattern as the app's progress hook: decoded byte counts per read chunk, reported only past the
		// retried attempt's watermark so already-counted bytes never re-fire. The rate fuse runs on those same bytes and
		// closes the lane asynchronously - never synchronously from the reader's own callback stack - so the retry ladder
		// recovers the take; a persistently trickling host burns its attempts and fails the transfer loudly.
		long budgetNanos = takeBudgetNanos(sliceBytes(take));
		RateFuse trickleFuse = new RateFuse(TAKE_RATE_FLOOR_BYTES_PER_SECOND, budgetNanos, 1, System::nanoTime, () -> {
			WireTrace.log("TAKE_FUSED", "object", objectName(), "item", take.start() + "-" + take.end(), "budget", budgetNanos);
			// Named at warn so a field report reads "pathological host", never the lane-died line the closed
			// lane's reader emits right after.
			LOGGER.warn("Take of {} on {} drained {} under the {} B/s floor across its {} s budget; fusing the lane and letting the retry ladder recover it",
					objectName(), session.endpointHost, ByteFormat.formatSize(sliceBytes(take)), TAKE_RATE_FLOOR_BYTES_PER_SECOND, budgetNanos / 1_000_000_000L);
			Connection fusedLane = lane.get();
			if (fusedLane != null) DownloadClient.NET_EXECUTOR.execute(() -> closeQuietly(fusedLane));
		});
		IntConsumer chunkCallback = bytes -> {
			takeBytes.addAndGet(bytes);
			long counted = Math.min(bytes, Math.max(0, takeBytes.get() - take.progressBase()));
			if (progress != null && counted > 0) progress.accept((int) counted);
			trickleFuse.onBytes(bytes);
		};
		CompletableFuture<Path> result;
		try {
			long sliceStart = PartialResume.sliceStart(take.start());
			// The tile takes its final name only once the take has filled it, so a take that dies mid-tile leaves a temp
			// that no reader counts and the next attempt starts the tile clean.
			Path slice = PartialResume.sliceTemp(directory, sliceStart);
			long writeAt = take.start() - sliceStart;
			long debit = ObjectTake.debit(take.start(), take.end());
			result = lanes.withSlot(take.lane(), debit, connection -> {
				lane.set(connection);
				return connection.sendDownloadFile(sha1Hex, ObjectTake.rangedSlice(slice, chunkCallback, take.start(), take.end(), fileSize, writeAt));
			}, submittedEpoch);
		} catch (Throwable submitFailure) {
			// The submit never produced a request: the settle path retries or books it like any other failure.
			WireTrace.log("TAKE_FAIL", "object", objectName(), "item", take.start() + "-" + take.end(), "error", submitFailure);
			onTakeSettled(take, takeBytes, submitFailure);
			return;
		}
		result.whenComplete((path, takeError) -> onTakeSettled(take, takeBytes, takeError));
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

	/** Halves the in-flight bound on a wire failure, never below one take per lane. */
	static int reduceOutstandingCap(int outstandingCap) {
		return Math.max(MIN_OUTSTANDING_TAKES, outstandingCap / 2);
	}

	private void onTakeSettled(Take take, AtomicLong takeBytes, Throwable takeError) {
		if (takeError == null) {
			try {
				PartialResume.promoteSlice(directory, PartialResume.sliceStart(take.start()), fileSize);
			} catch (IOException e) {
				takeError = new LocalStorageException("Failed to promote downloaded object slice", e);
			}
		}
		if (takeError instanceof RangeIgnoredException) session.markRangeIgnoredHost();
		boolean retried = takeError != null && take.attempt() < MAX_TAKE_ATTEMPTS && retryWorth(takeError);
		if (retried) {
			session.takeRetries.incrementAndGet();
			synchronized (lock) {
				// The retried range is still the same unsettled unit of work, but the wire already proved the
				// current bound was buying re-downloades. Shrink it before the retry is issued.
				if (outstandingCap > MIN_OUTSTANDING_TAKES) {
					outstandingCap = reduceOutstandingCap(outstandingCap);
					WireTrace.log("TAKE_WINDOW_DOWN", "object", objectName(), "cap", outstandingCap);
				}
				cleanSettles = 0;
			}
			// The barrier stays charged: the retried range is the same one unsettled unit of work. A throttled
			// answer waits out its window on the retry clock instead of an immediate re-issue - the wait never
			// parks a lane's reader, and an abort while waiting settles the take as cancelled, not restarted.
			long delayMillis = retryDelayMillis(takeError);
			Runnable retry = () -> {
				if (submittedEpoch != epoch.getAsInt()) {
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
		session.bytesDownloaded.addAndGet(takeBytes.get());
		boolean done;
		Throwable failure;
		synchronized (lock) {
			if (takeError == null && ++cleanSettles >= Math.max(MIN_OUTSTANDING_TAKES, outstandingCap)) {
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
		return new Take(sliceStart + present, sliceStart + expected - 1, laneIndex(), take.attempt() + 1, progressBase);
	}

	private void submitOpenEnded(long start) {
		session.takesSubmitted.incrementAndGet();
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
		lanes.withSlot(0, WIRE_CHUNK_BYTES, connection -> connection.sendDownloadFile(sha1Hex, ObjectTake.openEnded(writer, chunkCallback, start, fileSize)), submittedEpoch).whenComplete((path, takeError) -> {
			try {
				writer.close();
			} catch (IOException ignored) {
			}
			onTakeSettled(take, takeBytes, takeError);
		});
	}

	/** Pack-hygiene verdicts and the player's own no are final for the range; anything else (a lost lane, a timeout) is worth another attempt. */
	private static boolean permanentFailure(Throwable error) {
		if (error instanceof MissingObjectException || error instanceof UnauthorizedException || error instanceof StaleRangeException || error instanceof LocalStorageException
				|| error instanceof RangeIgnoredException || error instanceof UserRejectedCertificateException)
			return true;
		return error instanceof IOException && error.getMessage() != null && error.getMessage().contains("does not match the expected object size");
	}

	private boolean retryWorth(Throwable error) {
		return !permanentFailure(error) && submittedEpoch == epoch.getAsInt();
	}

	private void finish(Throwable failure) {
		if (failure == null) {
			try {
				if (!PartialResume.complete(directory, fileSize)) failure = new IOException("Incomplete slices for " + objectName());
			} catch (IOException e) {
				failure = e;
			}
		}
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
