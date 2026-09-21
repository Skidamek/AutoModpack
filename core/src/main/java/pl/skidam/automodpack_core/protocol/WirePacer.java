package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import pl.skidam.automodpack_core.utils.ByteFormat;

/**
 * The application-layer congestion window for the pipelined wire. The window counts the unsettled takes the caller may
 * keep on the lanes; it starts at one lane's pipeline depth - a smaller window cannot fill even one lane - doubles
 * every window-worth of clean settles up to the cap (lanes times pipeline depth), and halves only on a
 * congestion-shaped failure, floored at 1. Every number is a reused receipt; nothing here is hand-tuned.
 */
final class WirePacer {

	// Per-request duration samples beyond this multiple of the EMA are outliers (a lane failing), not latency signals.
	private static final double DURATION_OUTLIER_MULTIPLE = 8.0;

	/** One settled take's shape: clean progress, a congestion-shaped failure, or a permanent failure that only books telemetry. */
	enum Verdict {
		OK, CONGESTED, PERMANENT
	}

	private final int windowCap;
	private final int lanes;
	private final long startedNanos = System.nanoTime();
	private final Object lock = new Object();
	private final long[] laneBusyNanos;
	private final AtomicLong totalBytes = new AtomicLong();
	private int window;
	private int inFlight;
	private int windowPathPoints;
	private int cycleSettles;
	private int windowAtCycleStart;
	private double durationEmaNanos = -1;
	private String windowPath;

	WirePacer(int windowCap, int telemetryLanes) {
		this.lanes = telemetryLanes;
		this.windowCap = windowCap;
		this.laneBusyNanos = new long[telemetryLanes];
		this.windowAtCycleStart = Math.max(1, windowCap / Math.max(1, telemetryLanes));
		this.window = Math.min(windowCap, this.windowAtCycleStart);
		this.windowPath = String.valueOf(window);
	}

	/** Whether one more request fits the window; the caller submits on true. */
	boolean tryAcquire() {
		synchronized (lock) {
			if (inFlight >= window) return false;
			inFlight++;
			return true;
		}
	}

	/** Whether one more request would fit the window, without taking anything: a scheduling peek. */
	boolean hasRoom() {
		synchronized (lock) {
			return inFlight < window;
		}
	}

	/** Returns a credit without telemetry: the transfer's pump found nothing left to take. */
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

	/** Settles one take: bytes and duration book telemetry; the verdict drives the window - OK ramps it, CONGESTED halves it, PERMANENT only books. */
	void settle(Verdict verdict, long bytes, long nanos, int lane) {
		String event = null;
		synchronized (lock) {
			inFlight--;
			if (lane >= 0 && lane < laneBusyNanos.length) laneBusyNanos[lane] += Math.max(0, nanos);
			totalBytes.addAndGet(bytes);
			if (nanos > 0 && (durationEmaNanos < 0 || nanos < durationEmaNanos * DURATION_OUTLIER_MULTIPLE)) {
				durationEmaNanos = durationEmaNanos < 0 ? nanos : durationEmaNanos + 0.25 * (nanos - durationEmaNanos);
			}
			switch (verdict) {
				case OK -> {
					cycleSettles++;
					if (cycleSettles >= windowAtCycleStart) {
						if (window < windowCap) {
							window = Math.min(windowCap, window * 2);
							event = "ramp";
						}
						startCycle();
					}
				}
				case CONGESTED -> {
					window = Math.max(1, window / 2);
					event = "congestion halved it";
					startCycle();
				}
				case PERMANENT -> {
				}
			}
			if (event != null) {
				windowPath += "→" + window;
				if (windowPathPoints++ < 8) LOGGER.debug("[download] window {} ({})", window, event);
			}
		}
	}

	/** A cycle is one window-worth of clean settles, counted against the window at the cycle's start. */
	private void startCycle() {
		cycleSettles = 0;
		windowAtCycleStart = Math.max(1, window);
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
		return Math.max(1e-9, (System.nanoTime() - sinceNanos) / 1e9);
	}

	@Override
	public String toString() {
		synchronized (lock) {
			return "WirePacer[window=" + window + " inFlight=" + inFlight + " lanes=" + Arrays.toString(laneBusyNanos) + "]";
		}
	}
}
