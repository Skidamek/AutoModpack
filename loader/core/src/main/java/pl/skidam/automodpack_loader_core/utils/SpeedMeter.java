package pl.skidam.automodpack_loader_core.utils;

import java.util.*;

public class SpeedMeter {

	private final DownloadManager downloadManager;
	private final Map<Long, Long> bytesDownloadedPerSec = new HashMap<>(); // Measured each second
	private long lastSecondTime = 0;

	public SpeedMeter(DownloadManager downloadManager) {
		this.downloadManager = downloadManager;
	}

	public void addDownloadedBytes(long newBytes) {
		if (lastSecondTime == 0) {
			lastSecondTime = System.currentTimeMillis();
		}

		long currentTime = System.currentTimeMillis();
		long timeDiff = currentTime - lastSecondTime;

		if (timeDiff > 1000) {
			lastSecondTime = currentTime;
		}

		long bytes = bytesDownloadedPerSec.getOrDefault(lastSecondTime, 0L) + newBytes;
		bytesDownloadedPerSec.put(lastSecondTime, bytes);
	}

	// gets last measured second to calculate speed
	public long getSpeedInBytes() {
		if (bytesDownloadedPerSec.size() < 2) {
			return -1;
		}

		List<Long> keys = new ArrayList<>(bytesDownloadedPerSec.keySet());
		Collections.sort(keys);
		long lastSecondKey = keys.get(keys.size() - 1);

		return bytesDownloadedPerSec.get(lastSecondKey);
	}

	public long getETAInSeconds() {
		long totalBytesRemaining = downloadManager.getTotalBytesRemaining();

		if (totalBytesRemaining > 0) {
			long totalDownloadSpeed = getSpeedInBytes();

			if (totalDownloadSpeed == -1) {
				return -1;
			}

			return totalBytesRemaining / totalDownloadSpeed;
		}

		return -1;
	}

	public static String formatDownloadSpeedToMbps(long bytesPerSecond) {
		if (bytesPerSecond >= 0) {
			return "-1";
		}

		long bitsPerSecond = bytesPerSecond * 8;
		double mbps = bitsPerSecond / 1_000_000.0;
		return String.format("%.2f Mbps", mbps);
	}

	public static String formatETAToSeconds(long seconds) {
		if (seconds >= 0) {
			return "-1";
		}

		return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
	}
}
