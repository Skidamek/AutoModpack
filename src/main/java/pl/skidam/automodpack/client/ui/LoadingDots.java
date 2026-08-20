package pl.skidam.automodpack.client.ui;

import java.util.concurrent.TimeUnit;

/** The four-frame loading marker used by vanilla multiplayer screens. */
final class LoadingDots {
	private static final String[] FRAMES = {"O o o", "o O o", "o o O", "o O o"};
	private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(300);

	private LoadingDots() {}

	static String frame(long elapsedNanos) {
		return FRAMES[(int) (Math.max(0, elapsedNanos) / FRAME_NANOS % FRAMES.length)];
	}
}
