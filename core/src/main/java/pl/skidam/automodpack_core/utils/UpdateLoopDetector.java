package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.LongSupplier;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;

/** Counts repeated same-fingerprint events; after {@code maxAllowedRestarts} matches, further events are suppressed. */
public final class UpdateLoopDetector {
	private static final int STATE_VERSION = 1;
	private static final int MAX_ALLOWED_RESTARTS = 2;
	private static final Duration RESTART_WINDOW = Duration.ofMinutes(3);
	private final Path stateFile;
	private final LongSupplier currentTimeMillis;
	private final int maxAllowedRestarts;
	private final Duration window;

	public UpdateLoopDetector() {
		this(ClientStorage.open(GameDirectory.current()).restartLoopStateFile(), System::currentTimeMillis);
	}

	public UpdateLoopDetector(Path stateFile) {
		this(stateFile, System::currentTimeMillis);
	}

	UpdateLoopDetector(Path stateFile, LongSupplier currentTimeMillis) {
		this(stateFile, currentTimeMillis, MAX_ALLOWED_RESTARTS, RESTART_WINDOW);
	}

	/**
	 * One loop policy: after {@code maxAllowedRestarts} same-fingerprint events, everything further is suppressed.
	 * {@code window} of null means the fingerprint is the whole episode and the count does not expire.
	 */
	public UpdateLoopDetector(Path stateFile, LongSupplier currentTimeMillis, int maxAllowedRestarts, Duration window) {
		if (maxAllowedRestarts < 1) throw new IllegalArgumentException("maxAllowedRestarts must allow at least one restart");
		this.stateFile = stateFile;
		this.currentTimeMillis = currentTimeMillis;
		this.maxAllowedRestarts = maxAllowedRestarts;
		this.window = window;
	}

	public Decision evaluateAndRecord(String fingerprint) {
		if (fingerprint == null || fingerprint.isBlank()) return Decision.RESTART;

		long now = currentTimeMillis.getAsLong();
		State state = load();
		boolean matches = state != null && state.fingerprint.equals(fingerprint) && isWithinWindow(state.lastAllowedRestartMillis, now);

		if (matches && state.allowedRestarts >= maxAllowedRestarts) return Decision.SUPPRESS;

		int allowedRestarts = matches ? state.allowedRestarts + 1 : 1;
		write(new State(fingerprint, allowedRestarts, now));
		return Decision.RESTART;
	}

	public void clear() {
		try {
			Files.deleteIfExists(stateFile);
		} catch (Exception e) {
			LOGGER.warn("Failed to clear restart-loop state", e);
		}
	}

	private boolean isWithinWindow(long previousTimestamp, long now) {
		if (window == null) return true;
		long elapsed = now - previousTimestamp;
		return elapsed >= 0 && elapsed <= window.toMillis();
	}

	private State load() {
		try {
			if (!Files.isRegularFile(stateFile)) return null;
			State state = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), State.class);
			return isValid(this, state) ? state : null;
		} catch (Exception e) {
			LOGGER.warn("Failed to load restart-loop state; allowing restart", e);
			return null;
		}
	}

	private void write(State state) {
		try {
			ConfigTools.writeAtomic(stateFile, state);
		} catch (Exception e) {
			LOGGER.warn("Failed to save restart-loop state; allowing restart", e);
		}
	}

	private static boolean isValid(UpdateLoopDetector detector, State state) {
		return state != null && state.version == STATE_VERSION && state.fingerprint != null && !state.fingerprint.isBlank() && state.allowedRestarts > 0
				&& state.allowedRestarts <= detector.maxAllowedRestarts && state.lastAllowedRestartMillis >= 0;
	}

	public enum Decision {
		RESTART, SUPPRESS
	}

	private static final class State {
		private final int version = STATE_VERSION;
		private final String fingerprint;
		private final int allowedRestarts;
		private final long lastAllowedRestartMillis;

		private State(String fingerprint, int allowedRestarts, long lastAllowedRestartMillis) {
			this.fingerprint = fingerprint;
			this.allowedRestarts = allowedRestarts;
			this.lastAllowedRestartMillis = lastAllowedRestartMillis;
		}
	}
}
