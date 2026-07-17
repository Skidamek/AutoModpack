package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.privateDir;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Stops rapid updater restart loops which repeatedly fail to converge. */
public final class UpdateLoopDetector {
	private static final int STATE_VERSION = 1;
	private static final int MAX_ALLOWED_RESTARTS = 2;
	private static final Duration RESTART_WINDOW = Duration.ofMinutes(3);
	private static final Path STATE_FILE = privateDir.resolve("automodpack-restart-state.json");

	private final Path stateFile;
	private final LongSupplier currentTimeMillis;

	public UpdateLoopDetector() {
		this(STATE_FILE, System::currentTimeMillis);
	}

	UpdateLoopDetector(Path stateFile, LongSupplier currentTimeMillis) {
		this.stateFile = stateFile;
		this.currentTimeMillis = currentTimeMillis;
	}

	public Decision evaluateAndRecord(String fingerprint) {
		if (fingerprint == null || fingerprint.isBlank()) return Decision.RESTART;

		long now = currentTimeMillis.getAsLong();
		State state = load();
		boolean matches = state != null && state.fingerprint.equals(fingerprint) && isWithinWindow(state.lastAllowedRestartMillis, now);

		if (matches && state.allowedRestarts >= MAX_ALLOWED_RESTARTS) return Decision.SUPPRESS;

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
		long elapsed = now - previousTimestamp;
		return elapsed >= 0 && elapsed <= RESTART_WINDOW.toMillis();
	}

	private State load() {
		try {
			if (!Files.isRegularFile(stateFile)) { return null; }
			State state = GSON.fromJson(Files.readString(stateFile), State.class);
			return isValid(state) ? state : null;
		} catch (Exception e) {
			LOGGER.warn("Failed to load restart-loop state; allowing restart", e);
			return null;
		}
	}

	private void write(State state) {
		try {
			Path parent = stateFile.getParent();
			if (parent != null) { Files.createDirectories(parent); }
			Files.writeString(stateFile, GSON.toJson(state), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (Exception e) {
			LOGGER.warn("Failed to save restart-loop state; allowing restart", e);
		}
	}

	private static boolean isValid(State state) {
		return state != null && state.version == STATE_VERSION && state.fingerprint != null && !state.fingerprint.isBlank() && state.allowedRestarts > 0 && state.allowedRestarts <= MAX_ALLOWED_RESTARTS && state.lastAllowedRestartMillis >= 0;
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
