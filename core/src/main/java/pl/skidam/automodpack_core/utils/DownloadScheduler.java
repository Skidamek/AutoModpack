package pl.skidam.automodpack_core.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure download scheduling logic, no IO and no threads: given the queued files with known sizes, their candidate source
 * domains and the in-flight backlog per source, picks which file to download next and from which source. Owns one speed
 * estimate (bytes per second) per source, updated from real transfer samples via {@link #report}.
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

	/** The chosen file and the source domain to fetch it from. */
	public record Pick<T>(T identity, String sourceDomain) {}

	/**
	 * Picks the next file to download and the source to fetch it from. Files compete largest first (longest processing
	 * time first; ties keep the caller's list order) and the first one holding any candidate domain wins, so a file whose
	 * candidates the caller excluded all falls behind still-downloadable files instead of idling a slot. Among the
	 * winner's domains the one minimizing {@code (inFlightBytesRemaining(source) + sizeBytes) / speed(source)} is chosen:
	 * a fast source attracts work until its backlog catches up, and with equal speeds and sizes this degenerates to round
	 * robin over the caller's order. Returns null when no queued file has a candidate domain.
	 */
	public synchronized <T> Pick<T> pick(List<QueuedFile<T>> queue, Map<String, Long> inFlightBytesRemaining) {
		List<QueuedFile<T>> byLargest = new ArrayList<>(queue);
		byLargest.sort((first, second) -> Long.compare(second.sizeBytes(), first.sizeBytes()));
		for (QueuedFile<T> file : byLargest) {
			String bestDomain = null;
			double bestSeconds = Double.MAX_VALUE;
			for (String domain : file.sourceDomains()) {
				double seconds = (inFlightBytesRemaining.getOrDefault(domain, 0L) + file.sizeBytes()) / speedOf(domain);
				if (seconds < bestSeconds) {
					bestSeconds = seconds;
					bestDomain = domain;
				}
			}
			if (bestDomain != null) return new Pick<>(file.identity(), bestDomain);
		}
		return null;
	}

	/**
	 * Feeds one finished transfer (or a failed attempt that still received bytes) so the source's speed estimate
	 * converges on reality. Samples with no bytes or no duration carry no bandwidth information and are ignored, which
	 * also keeps the divisor in {@link #pick} away from zero and infinity.
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
