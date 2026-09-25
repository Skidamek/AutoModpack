package pl.skidam.automodpack_core.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure download scheduling logic, no IO and no threads: owns the source-domain choice for a queued file and one speed
 * estimate (bytes per second) per source, updated from real transfer samples via {@link #report}. The caller owns the
 * dispatch order of queued files (largest first); this class only weighs the candidate sources of whatever file it is
 * handed.
 */
public class DownloadScheduler {

	// How strongly a fresh sample replaces the previous estimate: speed = factor * sample + (1 - factor) * previous.
	// This is a convergence knob (how fast estimates track reality), not a capacity limit.
	private static final double EWMA_FACTOR = 0.5;

	// Optimistic seed for never-measured sources. Generous on purpose: capacity is free until touched, so unknown
	// sources attract work and get measured instead of starving behind pessimistic guesses.
	private static final double DEFAULT_SPEED_BYTES_PER_SECOND = 5.0 * 1024.0 * 1024.0;

	private final Map<String, Double> speeds = new HashMap<>();
	private double bestSpeedObserved = 0;

	/** A file waiting to be downloaded: opaque identity, its known size and the source domains it can come from, in the caller's preference order. */
	public record QueuedFile<T>(T identity, long sizeBytes, List<String> sourceDomains) {}

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
	 * Feeds one finished transfer (or a failed attempt that still received bytes) so the source's speed estimate
	 * converges on reality. Samples with no bytes or no duration carry no bandwidth information and are ignored, which
	 * also keeps the divisor in {@link #chooseDomain} away from zero and infinity.
	 */
	public synchronized void report(String source, long bytesReceived, long durationNanos) {
		if (source == null || bytesReceived <= 0 || durationNanos <= 0) return;
		double sample = bytesReceived / (durationNanos / 1_000_000_000.0);
		double updated = EWMA_FACTOR * sample + (1.0 - EWMA_FACTOR) * speedOf(source);
		speeds.put(source, updated);
		bestSpeedObserved = Math.max(bestSpeedObserved, updated);
	}

	private double speedOf(String source) {
		return speeds.getOrDefault(source, Math.max(bestSpeedObserved, DEFAULT_SPEED_BYTES_PER_SECOND));
	}
}
