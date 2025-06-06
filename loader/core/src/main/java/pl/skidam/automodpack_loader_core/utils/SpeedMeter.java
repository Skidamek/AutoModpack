package pl.skidam.automodpack_loader_core.utils;

import java.util.concurrent.ConcurrentSkipListMap;

public class SpeedMeter {

	private final DownloadManager downloadManager;
	private final ConcurrentSkipListMap<Long, Long> bytesDownloadedPerSec = new ConcurrentSkipListMap<>();
	private static final int MAX_ENTRIES = 3;

	public SpeedMeter(DownloadManager downloadManager) {
		this.downloadManager = downloadManager;
	}

	/**
	 * Add new bytes to the current download tally.
	 */
	public synchronized void addDownloadedBytes(long newBytes) {
		long bucketedTime = System.currentTimeMillis() / 1000 * 1000;

		bytesDownloadedPerSec.merge(bucketedTime, newBytes, Long::sum);

		while (bytesDownloadedPerSec.size() > MAX_ENTRIES) {
			bytesDownloadedPerSec.pollFirstEntry();
		}
	}

	/**
	 * Get the average download speed in bytes per second from the last few seconds.
	 */
	public long getAverageSpeedOfLastFewSeconds(int seconds) {
		long totalBytes = 0;
		int count = 0;

		for (Long bytes : bytesDownloadedPerSec.values()) {
			totalBytes += bytes;
			count++;
		}

		return count >= seconds ? totalBytes / count : -1;
	}

	/**
	 * Estimate the time remaining for the download in seconds.
	 */
	public long getETAInSeconds() {
		long totalBytesRemaining = downloadManager.getTotalBytesRemaining();
		long speed = getAverageSpeedOfLastFewSeconds(3);

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
	 * Format ETA into MM:SS format or HH:MM:SS format.
	 */
	public static String formatETAToSeconds(long seconds) {
		seconds++; // Increment by 1 to avoid showing 00:00:00 for 0 seconds
		if (seconds < 1) {
			return "-1";
		}

		if (seconds < 3600) {
			return String.format("%02d:%02d", seconds / 60, seconds % 60);
		}

		return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
	}
}
