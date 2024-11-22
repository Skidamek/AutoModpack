package pl.skidam.automodpack_loader_core.utils;

import java.util.*;

public class SpeedMeter {

	private final DownloadManager downloadManager;
	private final TreeMap<Long, Long> bytesDownloadedPerSec = new TreeMap<>();
	private static final int MAX_ENTRIES = 5;

	public SpeedMeter(DownloadManager downloadManager) {
		this.downloadManager = downloadManager;
	}

	/**
	 * Add new bytes to the current download tally.
	 */
	public void addDownloadedBytes(long newBytes) {
		long bucketedTime = System.currentTimeMillis() / 1000 * 1000;

		bytesDownloadedPerSec.merge(bucketedTime, newBytes, Long::sum);

		while (bytesDownloadedPerSec.size() > MAX_ENTRIES) {
			bytesDownloadedPerSec.pollFirstEntry();
		}
	}

	/**
	 * Get the download speed in bytes per second.
	 */
	public long getCurrentSpeedInBytes() {
		if (bytesDownloadedPerSec.isEmpty()) {
			return -1;
		}

		long lastTimeBucket = System.currentTimeMillis() / 1000 * 1000 - 1000;

		if (bytesDownloadedPerSec.containsKey(lastTimeBucket)) {
			return bytesDownloadedPerSec.get(lastTimeBucket);
		} else if (bytesDownloadedPerSec.containsKey(lastTimeBucket - 1000)) {
			return bytesDownloadedPerSec.get(lastTimeBucket - 1000);
		}

		return -1;
	}

	/**
	 * Estimate the time remaining for the download in seconds.
	 */
	public long getETAInSeconds() {
		long totalBytesRemaining = downloadManager.getTotalBytesRemaining();
		long speed = getCurrentSpeedInBytes();

		if (speed <= 0) {
			return -1;
		}

		return totalBytesRemaining / speed;
	}

	 /**
	 * Format download speed in Mbps.
	 */
	public static String formatDownloadSpeedToMbps(long currentSpeedInBytes) {
		if (currentSpeedInBytes < 0) {
			return "-1";
		}

		long bitsPerSecond = currentSpeedInBytes * 8;
		double mbps = bitsPerSecond / 1_000_000.0;
		return String.format("%.2f Mbps", mbps);
	}

	/**
	 * Format ETA into HH:MM:SS.
	 */
	public static String formatETAToSeconds(long seconds) {
		if (seconds < 0) {
			return "-1";
		}

		return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
	}
}
