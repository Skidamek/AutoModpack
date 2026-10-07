package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;

import pl.skidam.automodpack_core.utils.ByteFormat;

/**
 * What the launch-time update is doing right now, for loaders that can draw on screen before the game window exists
 * (Forge and NeoForge early loading screens). Core only records the stage; a loader adapter {@link #attach attaches}
 * a {@link Display} around {@code Preload}, and a daemon poller pushes the current line to it. Without an attached
 * display every call here is a cheap field write, so the update logic stays loader-agnostic.
 */
public final class LaunchProgress {
	/** A loader's early-screen sink. Called from the poller thread only. */
	public interface Display {
		/** {@code permille} is 0..1000 for a determinate step, or -1 while the step has no measurable progress. */
		void update(String label, int permille);

		/** Removes whatever the display added; called once when the launch phase ends. */
		void close();
	}

	private static final long POLL_INTERVAL_MS = 100;
	/** How long a terminal message (restart notice) stays readable before the process exits. */
	private static final long HOLD_BEFORE_EXIT_MS = TimeUnit.SECONDS.toMillis(5);

	private static volatile @Nullable Display display;
	private static volatile @Nullable Thread poller;
	private static volatile String stage = "";
	private static volatile @Nullable DownloadManager downloads;

	private LaunchProgress() {}

	/** Starts pushing progress to {@code newDisplay}. A null display (loader has no early screen) keeps everything a no-op. */
	public static synchronized void attach(@Nullable Display newDisplay) {
		if (newDisplay == null || display != null) return;
		display = newDisplay;
		stage("Starting");
		Thread thread = new Thread(LaunchProgress::pollLoop, "AutoModpack early progress");
		thread.setDaemon(true);
		poller = thread;
		thread.start();
	}

	/** Stops the poller and lets the display clean up. Safe to call when nothing is attached. */
	public static synchronized void detach() {
		Display current = display;
		Thread thread = poller;
		display = null;
		poller = null;
		downloads = null;
		stage = "";
		if (thread != null) {
			thread.interrupt();
			try {
				thread.join(POLL_INTERVAL_MS * 5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if (current != null) {
			try {
				current.close();
			} catch (RuntimeException e) {
				LOGGER.debug("Early progress display failed to close", e);
			}
		}
	}

	public static boolean isDisplayed() {
		return display != null;
	}

	/** Names the step now running; shown as an indeterminate line until a download attaches measurable progress. */
	public static void stage(String text) {
		stage = Objects.requireNonNull(text, "text");
	}

	/** While set, the line shows this download's bytes, files, speed and ETA instead of the bare stage. */
	public static void downloading(@Nullable DownloadManager manager) {
		downloads = manager;
	}

	/**
	 * Shows a final message and keeps it on screen for a few seconds, for the moment right before the launch exits on
	 * its own (a required restart). Returns immediately when no display is attached.
	 */
	public static void holdFinalMessage(String text) {
		if (display == null) return;
		downloads = null;
		stage(text);
		pushNow();
		try {
			Thread.sleep(HOLD_BEFORE_EXIT_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void pollLoop() {
		while (!Thread.currentThread().isInterrupted()) {
			pushNow();
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private static void pushNow() {
		Display current = display;
		if (current == null) return;
		try {
			DownloadManager manager = downloads;
			if (manager != null) current.update(downloadLine(manager), (int) Math.round(manager.getPrecisePercentage() * 10));
			else current.update("AutoModpack: " + stage, -1);
		} catch (RuntimeException e) {
			// The early screen is a convenience: a broken display must never take the update down with it.
			LOGGER.debug("Early progress display update failed", e);
		}
	}

	static String downloadLine(DownloadManager manager) {
		StringBuilder line = new StringBuilder("AutoModpack: downloading ");
		line.append(ByteFormat.formatSize(manager.getDownloadedBytes())).append(" / ").append(ByteFormat.formatSize(manager.getTotalBytes()));
		line.append(" (").append(manager.getStage()).append(" files)");
		long speed = manager.getDownloadSpeed();
		if (speed > 0) line.append(" - ").append(ByteFormat.formatSpeed(speed));
		long eta = manager.getETA();
		if (eta > 0) line.append(" - ").append(ByteFormat.formatETA(eta)).append(" left");
		return line.toString();
	}
}
