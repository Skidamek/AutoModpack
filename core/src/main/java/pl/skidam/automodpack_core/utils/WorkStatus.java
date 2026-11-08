package pl.skidam.automodpack_core.utils;

import java.util.Objects;

/**
 * The one transient description of the long work the player is waiting for. The engine writes a phase and, when it
 * knows them, completed/total units; the wait screen polls it. It is deliberately not durable: a new action resets it.
 */
public final class WorkStatus {
	public enum Phase {
		READING,
		CHECKING,
		DOWNLOADING,
		PREPARING,
		APPLYING,
		FINISHING
	}

	private static volatile Phase phase = Phase.READING;
	private static volatile long completed = -1;
	private static volatile long total = -1;

	private WorkStatus() {}

	public static void reset(Phase next) {
		phase(next);
	}

	public static void phase(Phase next) {
		phase = Objects.requireNonNull(next, "phase");
		completed = -1;
		total = -1;
	}

	public static void progress(long done, long all) {
		completed = Math.max(0, done);
		total = Math.max(0, all);
	}

	public static Phase phase() {
		return phase;
	}

	public static long completed() {
		return completed;
	}

	public static long total() {
		return total;
	}
}
