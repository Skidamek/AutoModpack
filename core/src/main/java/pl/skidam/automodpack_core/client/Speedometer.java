package pl.skidam.automodpack_core.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/** The run's displayed transfer rate and the time left at it, both read straight off one sliding window of byte counts. */
public class Speedometer {

	private final AtomicLong totalBytesReceived = new AtomicLong(0);
	private final AtomicLong totalBytesExpected = new AtomicLong(0);

	private final Deque<Snapshot> history = new ArrayDeque<>();

	// The window the displayed rate averages over. Long enough that one stalled read cannot make the bar jump (the fuse
	// tick is 15 s, so a link that pauses for a watchdog window still reads as its running average), short enough that a
	// finished download settles to zero on the next poll instead of trailing off for half a minute.
	private static final long WINDOW_MS = 15000;
	// Below this rate the remaining time is a number nobody can act on: at 1 KiB/s a 1 GiB remainder is twelve days.
	private static final long MIN_PREDICTABLE_BYTES_PER_SECOND = 1024;
	// A rate only reads as a stall past this: a link that paused once inside the window is still delivering.
	private static final long STALE_MS = 1000;

	private record Snapshot(long time, long bytes) {}

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

		history.addLast(new Snapshot(now, currentBytes));

		while (!history.isEmpty() && (now - history.getFirst().time > WINDOW_MS)) {
			history.removeFirst();
		}
	}

	private double windowSpeed(long now, long currentBytes) {
		if (history.isEmpty()) return 0.0;

		Snapshot oldest = history.getFirst();
		long timeDelta = now - oldest.time;
		long bytesDelta = currentBytes - oldest.bytes;

		if (timeDelta <= 0) return 0.0;

		return ((double) bytesDelta / timeDelta) * 1000.0;
	}

	public synchronized long getSpeed() {
		// A rate only reads as zero once nothing has arrived for a while; inside the window the last rate is still true.
		if (history.isEmpty() || System.currentTimeMillis() - history.getLast().time > STALE_MS) return 0;
		return (long) windowSpeed(System.currentTimeMillis(), totalBytesReceived.get());
	}

	public synchronized long getETA() {
		long receievedBytes = totalBytesReceived.get();
		if (receievedBytes <= 0) return -1;
		long remainingBytes = Math.max(0, totalBytesExpected.get() - receievedBytes);
		if (remainingBytes == 0) return 0;

		double realSpeed = windowSpeed(System.currentTimeMillis(), receievedBytes);
		if (realSpeed < MIN_PREDICTABLE_BYTES_PER_SECOND) return -1;

		return (long) (remainingBytes / realSpeed) + 1;
	}
}
