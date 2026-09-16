package pl.skidam.automodpack.client.ui.widget;

/** One tick-driven read countdown shared by every gated screen: call {@link #tick} once per frame, render {@link #secondsRemaining}. */
public final class Countdown {
	private final int durationTicks;
	private int ticksRemaining;

	public Countdown(int seconds) {
		this.durationTicks = seconds * 20;
		this.ticksRemaining = durationTicks;
	}

	/** Rearms the full duration, used when the screen re-enters or the gated content changed. */
	public void restart() {
		ticksRemaining = durationTicks;
	}

	/** Ends the countdown immediately, unlocking the gate. */
	public void finish() {
		ticksRemaining = 0;
	}

	/** Advances one tick; call from the screen's tick. */
	public void tick() {
		if (ticksRemaining > 0) ticksRemaining--;
	}

	/** True while the gate is locked. */
	public boolean running() {
		return ticksRemaining > 0;
	}

	/** The whole seconds left, rounded up so a fresh countdown reads its full length. */
	public int secondsRemaining() {
		return (ticksRemaining + 19) / 20;
	}
}
