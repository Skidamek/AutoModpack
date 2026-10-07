package pl.skidam.automodpack_core.utils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * One trickle detector, shared by every transport that pulls a body and can only answer a slow path by closing it: the
 * caller feeds the bytes the body delivered with {@link #onBytes}, a watchdog calls {@link #tick} so a window also
 * closes on a body that went quiet, and the fuse trips once {@code corroboratingWindows} windows in a row stayed under
 * the floor its transport configured. Every window anchors at the first byte, because queueing behind a sibling
 * transfer is flow control working and only a draining transfer can prove a trickle; a window that carried nothing breaks
 * the streak instead of counting as one. A floor of 0 keeps the fuse off. One fuse per body: it trips once and never
 * re-arms.
 */
public final class RateFuse {

	private final long floorBytesPerSecond;
	private final long windowNanos;
	private final int corroboratingWindows;
	private final LongSupplier nanoClock;
	private final Runnable trip;
	private final AtomicLong windowStartNanos = new AtomicLong();
	private final AtomicLong windowBytes = new AtomicLong();
	private final AtomicInteger subFloorWindows = new AtomicInteger();
	private final AtomicBoolean tripped = new AtomicBoolean();

	public RateFuse(long floorBytesPerSecond, long windowNanos, int corroboratingWindows, LongSupplier nanoClock, Runnable trip) {
		this.floorBytesPerSecond = floorBytesPerSecond;
		this.windowNanos = windowNanos;
		this.corroboratingWindows = corroboratingWindows;
		this.nanoClock = nanoClock;
		this.trip = trip;
	}

	/** True once the fuse closed its body, so the transport can report the fuse's verdict instead of a generic IO failure. */
	public boolean tripped() {
		return tripped.get();
	}

	public void onBytes(long bytes) {
		long now = nanoClock.getAsLong();
		windowStartNanos.compareAndSet(0, now);
		windowBytes.addAndGet(bytes);
		roll(now);
	}

	/** The watchdog's call, so a window whose bytes all arrived early still closes on time. */
	public void tick() {
		roll(nanoClock.getAsLong());
	}

	/** The one window roll: one read of the counter, so bytes arriving between the read and the reset count in this window exactly once. */
	private void roll(long now) {
		if (floorBytesPerSecond <= 0) return;
		long start = windowStartNanos.get();
		if (start == 0 || now - start < windowNanos) return;
		long bytes = windowBytes.getAndSet(0);
		windowStartNanos.set(now);
		long rate = (long) (bytes / ((now - start) / 1_000_000_000.0));
		if (bytes <= 0 || rate >= floorBytesPerSecond) {
			subFloorWindows.set(0);
			return;
		}
		if (subFloorWindows.incrementAndGet() < corroboratingWindows) return;
		if (!tripped.compareAndSet(false, true)) return;
		trip.run();
	}
}
