package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import pl.skidam.automodpack_core.utils.ByteFormat;

/**
 * The application-layer congestion window for the pipelined wire. The window is the number of unsettled requests the
 * caller may keep on the lanes; it starts at one and doubles each cycle - a cycle being one window-worth of good
 * settles - while the measured throughput still climbs at least {@link #PLATEAU_FRACTION}, freezes at plateau and
 * halves on any failure: slow start, with the plateau as its congestion-avoidance floor. Every bound is either a
 * reused receipt (the cap is lanes times pipeline depth) or measured live, so nothing here is tuned by hand.
 */
final class WirePacer {
	// A cycle that improved aggregate throughput by less than this fraction is a plateau: growth stops rather than bloating buffers.
	private static final double PLATEAU_FRACTION = 0.02;
	// Per-request duration samples beyond this multiple of the EMA are outliers (a lane failing), not latency signals.
	private static final double DURATION_OUTLIER_MULTIPLE = 8.0;

	private final int windowCap;
	private final int lanes;
	private final LongSupplier clock;
	private final long startedNanos;
	private final Object lock = new Object();
	private final long[] laneBusyNanos;
	private final AtomicLong totalBytes = new AtomicLong();
	private int window = 1;
	private int inFlight;
	private int windowPathPoints;
	private int cycleSettles;
	private int windowAtCycleStart = 1;
	private long cycleBytes;
	private long cycleStartNanos;
	private double cycleThroughput;
	private double durationEmaNanos = -1;
	private String windowPath = "1";

	WirePacer(int windowCap, int telemetryLanes) {
		this(windowCap, telemetryLanes, System::nanoTime);
	}

	/** The clock is injectable: throughput ratios only mean something on a deterministic time base. */
	WirePacer(int windowCap, int telemetryLanes, LongSupplier clock) {
		this.lanes = telemetryLanes;
		this.windowCap = windowCap;
		this.clock = clock;
		this.startedNanos = clock.getAsLong();
		this.cycleStartNanos = startedNanos;
		this.laneBusyNanos = new long[telemetryLanes];
	}

	/** Whether one more request fits the window; the caller submits on true. */
	boolean tryAcquire() {
		synchronized (lock) {
			if (inFlight >= window) return false;
			inFlight++;
			return true;
		}
	}

	/** Returns a credit without telemetry: a cache hit or a task that skipped its network leg. */
	void release() {
		synchronized (lock) {
			inFlight--;
		}
	}

	int inFlight() {
		synchronized (lock) {
			return inFlight;
		}
	}

	int window() {
		synchronized (lock) {
			return window;
		}
	}

	/** Settles one request: bytes and duration feed the growth decision; a failure halves the window. */
	public void settle(boolean failed, long bytes, long nanos, int lane) {
		String event = null;
		synchronized (lock) {
			inFlight--;
			if (lane >= 0 && lane < laneBusyNanos.length) laneBusyNanos[lane] += Math.max(0, nanos);
			totalBytes.addAndGet(bytes);
			if (nanos > 0 && (durationEmaNanos < 0 || nanos < durationEmaNanos * DURATION_OUTLIER_MULTIPLE)) {
				durationEmaNanos = durationEmaNanos < 0 ? nanos : durationEmaNanos + 0.25 * (nanos - durationEmaNanos);
			}
			if (failed) {
				window = Math.max(1, window / 2);
				event = "failure halved it";
				startCycle();
			} else {
				cycleSettles++;
				cycleBytes += bytes;
				if (cycleSettles >= windowAtCycleStart) {
					double throughput = cycleBytes / elapsedSeconds(cycleStartNanos);
					if (throughputPerCycleClimbed(throughput)) {
						if (window < windowCap) {
							window = Math.min(windowCap, window * 2);
							event = "throughput climbing";
						}
					} else {
						event = "plateau";
					}
					cycleThroughput = throughput;
					startCycle();
				}
			}
			if (event != null) {
				windowPath += "→" + window;
				if (windowPathPoints++ < 8) LOGGER.debug("[download] window {} ({})", window, event);
			}
		}
	}

	private boolean throughputPerCycleClimbed(double throughput) {
		return cycleThroughput == 0 || throughput >= cycleThroughput * (1 + PLATEAU_FRACTION);
	}

	private void startCycle() {
		cycleSettles = 0;
		cycleBytes = 0;
		windowAtCycleStart = Math.max(1, window);
		cycleStartNanos = clock.getAsLong();
	}

	/** The one-line receipt the sync summary carries: window path, duration estimate and per-lane settle rates. */
	public String summary() {
		synchronized (lock) {
			StringBuilder laneRates = new StringBuilder();
			double wallSeconds = elapsedSeconds(startedNanos);
			for (int lane = 0; lane < lanes; lane++) {
				if (lane > 0) laneRates.append('/');
				laneRates.append(ByteFormat.formatSpeed((long) (laneBusyNanos[lane] / wallSeconds)));
			}
			return "window " + windowPath + " · request ~" + (durationEmaNanos < 0 ? "?" : String.format(Locale.ROOT, "%.0fms", durationEmaNanos / 1e6)) + " · lane rates " + laneRates + " · "
					+ ByteFormat.formatSize(totalBytes.get()) + " total";
		}
	}

	private double elapsedSeconds(long sinceNanos) {
		return Math.max(1e-9, (clock.getAsLong() - sinceNanos) / 1e9);
	}

	@Override
	public String toString() {
		synchronized (lock) {
			return "WirePacer[window=" + window + " inFlight=" + inFlight + " lanes=" + Arrays.toString(laneBusyNanos) + "]";
		}
	}
}
