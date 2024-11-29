package pl.skidam.automodpack_loader_core.utils;

import java.util.concurrent.ConcurrentSkipListMap;

public class SpeedMeter {

	private final DownloadManager downloadManager;
	private final ConcurrentSkipListMap<Long, Long> bytesDownloadedPerSec = new ConcurrentSkipListMap<>();
	private static final int MAX_ENTRIES = 5;

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
	 * Get the download speed in bytes per second.
	 */
	public synchronized long getCurrentSpeedInBytes() {
		long lastTimeBucket = System.currentTimeMillis() / 1000 * 1000 - 1000;

		Long value = -1L;

		if (bytesDownloadedPerSec.containsKey(lastTimeBucket)) {
			value = bytesDownloadedPerSec.get(lastTimeBucket);
		} else if (bytesDownloadedPerSec.containsKey(lastTimeBucket - 1000)) {
			value = bytesDownloadedPerSec.get(lastTimeBucket - 1000);
		}

		return value != null ? value : -1;
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
