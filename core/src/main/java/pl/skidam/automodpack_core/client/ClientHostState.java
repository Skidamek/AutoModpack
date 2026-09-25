package pl.skidam.automodpack_core.client;

/** Remembers that this session's client hosting failed, so the player can be told once a world exists to show it in. */
public final class ClientHostState {
	private static volatile String failure;

	private ClientHostState() {}

	public static void hostingFailed(String reason) {
		failure = reason;
	}

	/** The pending failure, or null; taking it clears it, so the player is told once per session. */
	public static String takeFailure() {
		String taken = failure;
		failure = null;
		return taken;
	}
}
