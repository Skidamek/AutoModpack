package pl.skidam.automodpack_loader_core.utils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

public class Speedometer {

	private final AtomicLong totalBytesReceived = new AtomicLong(0);
	private final AtomicLong totalBytesExpected = new AtomicLong(0);

	// Sliding Window State
	private final Deque<Snapshot> history = new ArrayDeque<>();

	// Tuning
	private static final long WINDOW_MS = 15000; // Look back few seconds for accuracy
	private static final double SMOOTHING_FACTOR = 0.05; // 0.05 = Very smooth visual updates

	// Smooth Speed (For Display)
	private double visualSpeed = 0;

	// Helper Class
	private record Snapshot(long time, long bytes) { }

	public void addBytes(long bytes) {
		totalBytesReceived.addAndGet(bytes);
		update();
	}

	public void setExpectedBytes(long bytes) {
		totalBytesExpected.set(bytes);
	}

	private synchronized void update() {
		long now = System.currentTimeMillis();
		long currentBytes = totalBytesReceived.get();

		// 1. Add Snapshot
		history.addLast(new Snapshot(now, currentBytes));

		// 2. Prune old history
		while (!history.isEmpty() && (now - history.getFirst().time > WINDOW_MS)) {
			history.removeFirst();
		}

		// 3. Calculate Real-Time Window Speed
		double instantSpeed = calculateWindowSpeed(now, currentBytes);

		// 4. Update Visual Speed (EMA)
		if (visualSpeed == 0 || instantSpeed == 0) {
			visualSpeed = instantSpeed;
		} else {
			visualSpeed = (instantSpeed * SMOOTHING_FACTOR) + (visualSpeed * (1.0 - SMOOTHING_FACTOR));
		}
	}

	private double calculateWindowSpeed(long now, long currentBytes) {
		if (history.isEmpty()) return 0.0;

		Snapshot oldest = history.getFirst();
		long timeDelta = now - oldest.time;
		long bytesDelta = currentBytes - oldest.bytes;

		if (timeDelta <= 0) return 0.0;

		return ((double) bytesDelta / timeDelta) * 1000.0;
	}

	public synchronized long getSpeed() {
		if (history.isEmpty()) return 0;

		// Force an update if no data came in recently (handle stall)
		if (System.currentTimeMillis() - history.getLast().time > 1000) {
			return 0;
		}
		return (long) visualSpeed;
	}

	public synchronized long getETA() {
		long remainingBytes = Math.max(0, totalBytesExpected.get() - totalBytesReceived.get());
		if (remainingBytes == 0) return 0;

		// Get the "Real" speed from the window (not the smoothed one)
		double realSpeed = calculateWindowSpeed(System.currentTimeMillis(), totalBytesReceived.get());

		// Safety: If speed is 0 or very low, return -1 (unknown)
		if (realSpeed < 1024) return -1;

		return (long) (remainingBytes / realSpeed);
	}
}