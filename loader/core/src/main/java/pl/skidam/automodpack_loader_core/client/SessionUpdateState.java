package pl.skidam.automodpack_loader_core.client;

/** Remembers, for the lifetime of this process, that an update wrote content the running game has not loaded. */
public final class SessionUpdateState {
	private static volatile boolean appliedContentNotLoaded;

	private SessionUpdateState() {}

	/** Marks that a mid-session apply changed content; only a game restart loads it. */
	public static void markAppliedContentNotLoaded() {
		appliedContentNotLoaded = true;
	}

	public static boolean hasAppliedContentNotLoaded() {
		return appliedContentNotLoaded;
	}
}
