package pl.skidam.automodpack_core.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One daemon thread that forces held files while their owner keeps downloading; it starts lazily on the first submit, so a
 * run that never overlaps an object force never pays for a thread. Failures are per item: one object that cannot be
 * forced fails only its own task, and the owner reads that failure after {@link #awaitIdle()}.
 *
 * <p>
 * The queue is unbounded, so a producer never waits for force IO or capacity. {@link #closeAdmission()} is the only stop
 * signal - the pump exits after attempting everything admitted before it, so {@link #awaitIdle()} terminates by
 * construction and never depends on a producer nudging a parked thread.
 */
final class OverlappedForcer {
	private static final Logger LOGGER = LogManager.getLogger(OverlappedForcer.class);

	/** The force itself; a test can fail one path with a plain {@link IOException} instead of a real media fault. */
	@FunctionalInterface
	interface Forcer {
		void force(Path held) throws IOException;
	}

	static final class Work {
		final ObjectBatch.Held object;
		/** Written by the one thread that attempts this item, read by its owner after {@link OverlappedForcer#awaitIdle()}. */
		Throwable failure;
		/** The last attempt returned; only a forced object may publish. */
		boolean forced;
		Work(ObjectBatch.Held object) {
			this.object = object;
		}
	}

	/** The only item without an object: it ends the pump after every item admitted before it. */
	private static final Work POISON = new Work(null);

	private final Forcer forcer;
	private final LinkedBlockingQueue<Work> queue = new LinkedBlockingQueue<>();
	private final Object admission = new Object();
	private boolean closed;
	private boolean started;
	private boolean startFailed;
	private volatile Thread thread;

	OverlappedForcer(Forcer forcer) {
		this.forcer = Objects.requireNonNull(forcer, "forcer");
	}

	/** Appends without waiting; false means the pump will not attempt this item and its owner has to force it itself. */
	boolean submit(Work work) {
		synchronized (admission) {
			if (closed || !start()) return false;
			queue.add(work);
			return true;
		}
	}

	/** One force attempt for the item's owner; the caller must be the only thread touching this item. */
	void force(Work work) throws IOException {
		forcer.force(work.object.file());
	}

	/** No item submitted after this returns reaches the pump; the items admitted before it still do, then the pump exits. */
	void closeAdmission() {
		synchronized (admission) {
			if (closed) return;
			closed = true;
			queue.add(POISON);
		}
	}

	/**
	 * Waits until every admitted item has been attempted and returns whether the caller was interrupted, with the flag
	 * left clear so the remaining uninterruptible work of a publishing caller is not poisoned by it.
	 */
	boolean awaitIdle() {
		boolean interrupted = Thread.interrupted();
		Thread pump = thread;
		if (pump == null) return interrupted;
		while (true) {
			try {
				pump.join();
				return interrupted;
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
	}

	private boolean start() {
		if (started) return !startFailed;
		started = true;
		try {
			Thread pump = new Thread(this::work, "AutoModpackObjectForce");
			pump.setDaemon(true);
			thread = pump;
			pump.start();
			return true;
		} catch (Throwable failure) {
			// The thread is an optimization: every submitted item falls back to its owner's join-time force.
			startFailed = true;
			LOGGER.warn("Could not start the object force daemon; held objects are forced at join", failure);
			return false;
		}
	}

	private void work() {
		while (true) {
			Work next;
			try {
				next = queue.take();
			} catch (InterruptedException ignored) {
				// An unrelated launcher interrupt is not cancellation and must not abandon queued forces.
				continue;
			}
			if (next == POISON) return;
			attempt(next);
		}
	}

	/** A pump attempt fails only its own item: the failure is logged where it is captured because a cancelled run never reads it. */
	private void attempt(Work work) {
		work.failure = null;
		try {
			forcer.force(work.object.file());
			work.forced = true;
		} catch (Throwable failure) {
			LOGGER.warn("Force of {} failed", work.object.file(), failure);
			work.failure = failure;
		}
	}
}
