package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.DATA_ROOT_PROPERTY;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LEASE_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LOG_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_PRESENCE_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_STATUS_FILE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

import com.google.gson.Gson;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.WindowsLockProbe;

public final class DetachedUpdateHelper {
	// Receipt: the work lease is held only while Recover retries. UpdateRecovery.retryBudgetMillis() of sleeps plus
	// per-attempt IO fits well inside this wait. A helper still waiting on the parent does not hold this lease, so boot
	// recovers immediately. Past this wait the applying helper is stuck, and hanging this boot behind it serves nobody.
	private static final Duration HELPER_LEASE_WAIT = Duration.ofMinutes(3);
	// A released lease is noticed within half a second, the helper's own initial backoff step.
	private static final long LEASE_POLL_MILLIS = 500;

	private DetachedUpdateHelper() {}

	/**
	 * Spawns the helper for this process's PID and start instant. Call as soon as this process cannot finish the apply;
	 * the helper waits for that process itself. Do not start it from a shutdown hook. A helper already present for this
	 * instance is left to finish after this process exits.
	 */
	public static void launch() throws IOException {
		if (presenceHeld()) {
			LOGGER.info("A detached update helper is already running; it will finish after this process exits");
			return;
		}
		Path runtimeJar = RuntimeJar.current();
		// The game process exits right after this launch, so inherited streams would die with it; the helper's story must outlive the game in a file.
		// Append: a second helper that loses the lease still starts with this redirect already open, and must not truncate the running helper's log.
		Path helperLog = GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize();
		// The helper has no loader service. Pin this process's already-resolved root as a JVM property before the main class; configuredRoot prefers it over env and role defaults.
		String environment = LOADER_MANAGER == null || LOADER_MANAGER.getEnvironmentType() == null ? "" : LOADER_MANAGER.getEnvironmentType().name();
		DataRootResolver.Location dataLocation = DataRootResolver.resolve(GameDirectory.current(), LOADER_MANAGER == null ? null : LOADER_MANAGER.getEnvironmentType());
		String parentStart = ProcessHandle.current().info().startInstant().map(instant -> Long.toString(instant.toEpochMilli())).orElse("-");
		DetachedProcesses.start(UpdateHelperMain.class, List.of("-D" + DATA_ROOT_PROPERTY + "=" + dataLocation.root()),
				List.of(Long.toString(ProcessHandle.current().pid()), environment, parentStart), GameDirectory.current(), helperLog, helperClasspath(runtimeJar));
		LOGGER.info("Launched detached update helper for the latest pending transaction from {} (environment {}); its output goes to {}", runtimeJar, environment.isBlank() ? "unknown" : environment, helperLog);
	}

	/** True when a helper process already holds presence, including while it waits for the game to exit. */
	static boolean presenceHeld() throws IOException {
		Path presenceFile = GameDirectory.current().resolve(HELPER_PRESENCE_FILE).toAbsolutePath().normalize();
		if (!Files.isRegularFile(presenceFile, LinkOption.NOFOLLOW_LINKS)) return false;
		try (FileChannel channel = FileChannel.open(presenceFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			FileLock probe;
			try {
				probe = channel.tryLock();
			} catch (OverlappingFileLockException e) {
				return true;
			}
			if (probe == null) return true;
			probe.release();
			return false;
		} catch (NoSuchFileException e) {
			return false;
		}
	}

	/**
	 * True after waiting out a helper that already held the work lease, so the caller should retry recovery once.
	 * False when none is applying, or when it still held the lease after {@link #HELPER_LEASE_WAIT} and this
	 * boot proceeds without it. The applying helper keeps that lease and finishes on its own.
	 */
	public static boolean awaitRunningHelper() throws IOException {
		Path leaseFile = GameDirectory.current().resolve(HELPER_LEASE_FILE).toAbsolutePath().normalize();
		if (!Files.isRegularFile(leaseFile, LinkOption.NOFOLLOW_LINKS)) return false;
		try (FileChannel channel = FileChannel.open(leaseFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			FileLock probe;
			try {
				probe = channel.tryLock();
			} catch (OverlappingFileLockException e) {
				return false;
			}
			if (probe != null) {
				probe.release();
				return false;
			}
			String holder = WindowsLockProbe.describeHeld(leaseFile);
			if (holder == null) LOGGER.info("Waiting for the detached update helper to finish");
			else LOGGER.info("Waiting for the detached update helper to finish; the lease is held by: {}", holder);
			long deadline = System.nanoTime() + HELPER_LEASE_WAIT.toNanos();
			while (System.nanoTime() < deadline) {
				try (FileLock acquired = channel.tryLock()) {
					if (acquired != null) return true;
				}
				try {
					Thread.sleep(LEASE_POLL_MILLIS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for the detached update helper lease", e);
				}
			}
			LOGGER.error("The detached update helper still holds the lease after {} minutes; continuing without it, its own log is at {}", HELPER_LEASE_WAIT.toMinutes(),
					GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize());
			return false;
		}
	}

	/**
	 * Mirrors the previous helper run into the process log, so a latest.log paste is enough. Call after boot recovery
	 * has waited out a still-running helper. The helper writes {@link HelperStatusFields} before releasing the work
	 * lease; this never parses last-helper-run.log. The document is consumed after a mirror so a later boot cannot
	 * reprint an old outcome.
	 */
	public static void mirrorRecentRuns() {
		mirrorRecentRuns(GameDirectory.current().resolve(HELPER_STATUS_FILE).toAbsolutePath().normalize());
	}

	static void mirrorRecentRuns(Path statusFile) {
		try {
			ConfigTools.readCached(statusFile, HelperStatusFields.class, "Helper run status", Function.identity()).ifPresent(status -> {
				mirrorStatus(status);
				try {
					DurableFiles.discard(statusFile);
				} catch (IOException e) {
					LOGGER.warn("Cannot consume the detached helper's status from {}", statusFile.getFileName(), e);
				}
			});
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Cannot read the detached helper's status from {}", statusFile.getFileName(), e);
		}
	}

	static void writeRunStatus(Path file, boolean failed, String duration, List<String> details) throws IOException {
		HelperStatusFields fields = new HelperStatusFields();
		fields.failed = failed;
		fields.duration = duration == null ? "" : duration;
		fields.details = details == null ? new ArrayList<>() : new ArrayList<>(details);
		ConfigTools.writeCached(file, fields);
	}

	static void mirrorStatus(HelperStatusFields status) {
		if (!status.failed) {
			if (status.duration != null && !status.duration.isBlank()) LOGGER.info("Previous AutoModpack helper run recovered the pending update ({})", status.duration);
			else LOGGER.info("Previous AutoModpack helper run recovered the pending update");
			return;
		}
		LOGGER.error("Previous AutoModpack helper run failed");
		if (status.details == null) return;
		for (String message : status.details) LOGGER.error("{}", message);
	}

	/** Rebuildable receipt of the last helper run. Document class, not record: this graph lands on disk where the game's Gson cannot read records. */
	public static class HelperStatusFields {
		public boolean failed;
		public String duration = "";
		public List<String> details = new ArrayList<>();
	}

	private static List<Path> helperClasspath(Path runtimeJar) throws IOException {
		List<Path> classpath = new ArrayList<>();
		classpath.add(runtimeJar);
		classpath.add(RuntimeJar.dependency(Gson.class));
		classpath.add(RuntimeJar.dependency(LogManager.class));
		classpath.add(RuntimeJar.dependency(LoggerContext.class));
		return classpath;
	}

}
