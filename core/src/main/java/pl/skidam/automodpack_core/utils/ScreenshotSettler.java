package pl.skidam.automodpack_core.utils;

/**
 * The autotest screenshot settling policy, shared with the in-game test bridge: a target screen counts as settled only
 * after it rendered unchanged without an overlay on two consecutive frames, and counts as gone when two consecutive
 * frames rendered some other screen. Screens are compared by identity, never by equality. Pure decision - the caller
 * captures the frames and performs the actual screenshot.
 */
public final class ScreenshotSettler {
	public enum Observation {
		WAIT, SETTLED, TARGET_GONE
	}

	public record Frame(Object screen, boolean overlayVisible) {}

	private final Object targetScreen;
	private Frame previous;

	public ScreenshotSettler(Object targetScreen) {
		this.targetScreen = targetScreen;
	}

	public Observation observe(Frame frame) {
		Frame previous = this.previous;
		this.previous = frame;
		// A replaced target screen can never settle; two consecutive frames of another screen prove it is gone.
		if (previous != null && frame.screen() != targetScreen && previous.screen() != targetScreen) return Observation.TARGET_GONE;
		return frame.screen() == targetScreen && previous != null && previous.screen() == targetScreen && !frame.overlayVisible() && !previous.overlayVisible()
				? Observation.SETTLED
				: Observation.WAIT;
	}
}
