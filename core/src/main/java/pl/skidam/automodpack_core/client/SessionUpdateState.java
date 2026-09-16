package pl.skidam.automodpack_core.client;

/** Remembers, for the lifetime of this process, that an update wrote content the running game has not loaded. */
public final class SessionUpdateState {
	private static volatile boolean appliedContentNotLoaded;

	private SessionUpdateState() {}

	/** Marks that a mid-session apply changed content; only a game restart loads it. */
	public static void markAppliedContentNotLoaded() {
		appliedContentNotLoaded = true;
	}

	/** This process entered a world, so a later disconnect is not a failed join of unloaded content. */
	public static void worldEntered() {
		appliedContentNotLoaded = false;
	}

	public static boolean hasAppliedContentNotLoaded() {
		return appliedContentNotLoaded;
	}
}
