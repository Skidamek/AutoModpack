package pl.skidam.automodpack_core.client;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import pl.skidam.automodpack_core.utils.DownloadScheduler;

/**
 * Everything one attempt's written bytes mean: global progress, the bytes still owed for the scheduler's backlog, the
 * interval sample the scheduler estimates the domain's speed from, and the trickle floor this attempt's body fuse runs
 * with. The state is atomics because the host hook runs concurrently on up to five lane reader threads while the platform
 * hook runs on its worker.
 */
final class TransferSampler {

	// One speed-sample window per attempt, anchored at the first byte so TLS and head latency never pollute a sample:
	// even at the wire's 4 KiB/s trickle floor a window still holds measurable bytes, and 9 of them fit inside the 90 s
	// TRANSFER_WRITE_STALL_TIMEOUT a stalled body dies at - a crawling path reports long before its end.
	private static final long SPEED_SAMPLE_WINDOW_NANOS = Duration.ofSeconds(10).toNanos();

	private final DownloadScheduler scheduler;
	private final DownloadData data;
	private final IntConsumer progress;
	private final long rateFuseFloor;
	private final Runnable sampleReported;
	private final AtomicLong windowStartNanos = new AtomicLong();
	private final AtomicLong windowBytes = new AtomicLong();

	TransferSampler(DownloadScheduler scheduler, DownloadData data, IntConsumer progress, long rateFuseFloor, Runnable sampleReported) {
		this.scheduler = scheduler;
		this.data = data;
		this.progress = progress;
		this.rateFuseFloor = rateFuseFloor;
		this.sampleReported = sampleReported;
	}

	IntConsumer progressHook() {
		return bytes -> {
			progress.accept(bytes);
			data.remainingBytes.addAndGet(-bytes);
			onBytes(bytes);
		};
	}

	/** The floor this attempt's body fuse runs with, or 0 to keep it off. */
	long rateFuseFloor() {
		return rateFuseFloor;
	}

	private void onBytes(int bytes) {
		long now = System.nanoTime();
		windowStartNanos.compareAndSet(0, now);
		windowBytes.addAndGet(bytes);
		rollWindow(now);
	}

	/** Reports the ripe window and starts a fresh one; the winner of the start swap reports, concurrent lanes move on. */
	private void rollWindow(long now) {
		long start = windowStartNanos.get();
		if (start == 0 || now - start < SPEED_SAMPLE_WINDOW_NANOS) return;
		if (!windowStartNanos.compareAndSet(start, now)) return;
		report(windowBytes.getAndSet(0), now - start);
	}

	/** Reports the final partial window at attempt end, whatever its duration. */
	void flush() {
		long start = windowStartNanos.get();
		if (start == 0) return;
		long now = System.nanoTime();
		if (now <= start) return;
		report(windowBytes.getAndSet(0), now - start);
	}

	/** One measurement: an empty or invalid window carries no rate. */
	private void report(long bytes, long durationNanos) {
		if (bytes <= 0 || durationNanos <= 0) return;
		// A fresh estimate is the only thing the rescue policy reads, so the run is pumped whenever one lands rather
		// than on every window that fed it.
		if (scheduler.report(data.bookedDomain(), bytes, durationNanos)) sampleReported.run();
	}
}
