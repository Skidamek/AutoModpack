package pl.skidam.automodpack_core.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import pl.skidam.automodpack_core.protocol.NetUtils;

/**
 * Pure download scheduling logic, no IO and no threads: owns the source-domain choice for a queued file and one speed
 * estimate (bytes per second) per source, updated from real transfer samples via {@link #report}. The first real
 * measurement of a source IS its estimate - a ratio between two sources must not be decided by an optimistic seed - and
 * later measurements converge on it. A domain without a fresh sample counts as unmeasured everywhere: its estimate
 * describes a path that may be long gone, so the optimistic seed re-attracts work and one backlog-capped dispatch
 * re-measures it instead of trusting a fossil. The caller owns the dispatch order of queued files (largest first); this
 * class only weighs the candidate sources of whatever file it is handed.
 */
public class DownloadScheduler {

	// How strongly a fresh sample replaces the previous estimate: speed = factor * sample + (1 - factor) * previous.
	// This is a convergence knob (how fast estimates track reality), not a capacity limit.
	private static final double EWMA_FACTOR = 0.5;

	// Optimistic seed for never-measured sources. Generous on purpose: capacity is free until touched, so unknown
	// sources attract work and get measured instead of starving behind pessimistic guesses.
	private static final double DEFAULT_SPEED_BYTES_PER_SECOND = 5.0 * 1024.0 * 1024.0;

	// A domain unsampled for this long counts as unmeasured everywhere. Same cadence as NetUtils.HTTP_IDLE_REAP_SECONDS -
	// unused-for-a-while gets recycled.
	static final long SAMPLE_STALE_NANOS = TimeUnit.SECONDS.toNanos(NetUtils.HTTP_IDLE_REAP_SECONDS);

	// The floor on a domain's accumulated evidence: a handful of bytes measured over microseconds claims a rate no link
	// has, and one such estimate is enough to send the whole run's traffic down the wrong path - so windows accumulate
	// per domain until they carry enough bytes to be trusted.
	public static final long MIN_SAMPLE_BYTES = 40 * 1024;

	private final Map<String, DomainState> states = new HashMap<>();
	private final LongSupplier nanoClock;
	private double bestSpeedObserved = 0;

	private static final class DomainState {
		// NaN until the first real measurement lands in it: a state that never carried a sample must not read as one.
		double speed = Double.NaN;
		long lastSampleNanos;
		long pendingBytes;
		long pendingNanos;
	}

	/** A file waiting to be downloaded: opaque identity, its known size and the source domains it can come from, in the caller's preference order. */
	public record QueuedFile<T>(T identity, long sizeBytes, List<String> sourceDomains) {}

	public DownloadScheduler() {
		this(System::nanoTime);
	}

	/** The injectable clock exists so staleness is testable without sleeping. */
	DownloadScheduler(LongSupplier nanoClock) {
		this.nanoClock = nanoClock;
	}

	/**
	 * Chooses the source domain to fetch this file from: the one minimizing {@code (inFlightBytesRemaining(source) +
	 * sizeBytes) / speed(source)} - a fast source attracts work until its backlog catches up, and with equal speeds and
	 * sizes this degenerates to round robin over the caller's preference order. Returns null when the file has no
	 * candidate domain.
	 */
	public synchronized <T> String chooseDomain(QueuedFile<T> file, Map<String, Long> inFlightBytesRemaining) {
		String bestDomain = null;
		double bestSeconds = Double.MAX_VALUE;
		for (String domain : file.sourceDomains()) {
			double seconds = (inFlightBytesRemaining.getOrDefault(domain, 0L) + file.sizeBytes()) / speedOf(domain);
			if (seconds < bestSeconds) {
				bestSeconds = seconds;
				bestDomain = domain;
			}
		}
		return bestDomain;
	}

	/**
	 * Feeds one finished transfer window (or a failed attempt that still received bytes) so the source's speed estimate
	 * converges on reality. Windows with no bytes or no duration carry no bandwidth information and are ignored, which
	 * also keeps the divisor in {@link #chooseDomain} away from zero and infinity. Small windows accumulate per domain
	 * until they cross {@link #MIN_SAMPLE_BYTES}; returns true only when a new blended estimate landed from that
	 * accumulated evidence. Freshness is judged against the previous sample's stamp, so an estimate that went stale is
	 * replaced outright instead of being blended in as if it were still live.
	 */
	public synchronized boolean report(String source, long bytesReceived, long durationNanos) {
		if (source == null || bytesReceived <= 0 || durationNanos <= 0) return false;
		DomainState state = states.computeIfAbsent(source, ignored -> new DomainState());
		state.pendingBytes += bytesReceived;
		state.pendingNanos += durationNanos;
		if (state.pendingBytes < MIN_SAMPLE_BYTES) return false;
		double sample = state.pendingBytes / (state.pendingNanos / 1_000_000_000.0);
		state.pendingBytes = 0;
		state.pendingNanos = 0;
		long now = nanoClock.getAsLong();
		Double previous = freshMeasurement(state, now);
		state.lastSampleNanos = now;
		state.speed = previous == null ? sample : EWMA_FACTOR * sample + (1.0 - EWMA_FACTOR) * previous;
		bestSpeedObserved = Math.max(bestSpeedObserved, state.speed);
		return true;
	}

	/** The domain's current speed estimate in bytes per second; null when never measured or the last measurement went stale. */
	public synchronized Double measuredSpeed(String source) {
		DomainState state = states.get(source);
		return freshMeasurement(state, nanoClock.getAsLong());
	}

	private double speedOf(String source) {
		DomainState state = states.get(source);
		Double measured = freshMeasurement(state, nanoClock.getAsLong());
		return measured != null ? measured : Math.max(bestSpeedObserved, DEFAULT_SPEED_BYTES_PER_SECOND);
	}

	/** The domain's own last measurement while it is fresh, null otherwise: the optimistic seed is a dispatch guess and never an estimate. */
	private static Double freshMeasurement(DomainState state, long now) {
		if (state == null || Double.isNaN(state.speed) || now - state.lastSampleNanos > SAMPLE_STALE_NANOS) return null;
		return state.speed;
	}
}
