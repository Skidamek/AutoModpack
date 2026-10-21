package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LEASE_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_PRESENCE_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_STATUS_FILE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.SelfUpdateSwap;
import pl.skidam.automodpack_core.update.UpdateCommit;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.JarUtils;

public final class UpdateHelperMain {
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(Locale.ROOT);

	private UpdateHelperMain() {}

	public static void main(String[] arguments) {
		DetachedProcesses.ignoreHangup();
		int exitCode = run(arguments);
		System.out.flush();
		if (exitCode != 0) System.exit(exitCode);
	}

	static int run(String[] arguments) {
		long start = System.nanoTime();
		RunLog log = new RunLog();
		try {
			return runOnce(arguments, log, start) ? 1 : 0;
		} catch (Exception failure) {
			log.fail("Fatal helper failure: " + failure);
			failure.printStackTrace();
			return 1;
		} finally {
			log.narrate("Update helper run ended after " + seconds(System.nanoTime() - start));
		}
	}

	private static String seconds(long nanos) {
		return String.format(Locale.ROOT, "%.1fs", nanos / 1_000_000_000.0);
	}

	private static boolean runOnce(String[] arguments, RunLog log, long start) throws IOException, InterruptedException {
		if (arguments.length < 1 || arguments.length > 3) throw new IOException("Expected parent PID, optional environment, and optional parent start epoch milli");
		long parentPid = Long.parseLong(arguments[0]);
		if (parentPid <= 0 || parentPid == ProcessHandle.current().pid()) throw new IOException("Invalid parent PID");
		LoaderManagerService.EnvironmentType environment = environment(arguments);
		Long parentStartEpochMilli = parentStartEpochMilli(arguments);

		Path gameDirectory = GameDirectory.current();
		DataRootResolver.Location dataLocation = DataRootResolver.resolve(gameDirectory, environment);
		logStart(parentPid, environment, gameDirectory, dataLocation, log);
		Path helperDirectory = gameDirectory.resolve(HELPER_PRESENCE_FILE).toAbsolutePath().normalize().getParent();
		Files.createDirectories(helperDirectory);
		Path presenceFile = gameDirectory.resolve(HELPER_PRESENCE_FILE).toAbsolutePath().normalize();
		Path leaseFile = gameDirectory.resolve(HELPER_LEASE_FILE).toAbsolutePath().normalize();
		try (FileChannel presenceChannel = FileChannel.open(presenceFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			FileLock presence = tryLock(presenceChannel);
			if (presence == null) {
				log.narrate("Another update helper is already running; exiting");
				return false;
			}
			try (presence) {
				waitForGameExit(parentPid, parentStartEpochMilli, log);
				try (FileChannel leaseChannel = FileChannel.open(leaseFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
					FileLock lease = tryLock(leaseChannel);
					if (lease == null) {
						log.narrate("Another update helper already holds the work lease; exiting");
						return false;
					}
					try (lease) {
						try {
							return recoverPending(gameDirectory, dataLocation, log);
						} catch (Exception failure) {
							log.fail("Fatal helper failure: " + failure);
							failure.printStackTrace();
							return true;
						} finally {
							writeStatus(log, start);
						}
					}
				}
			}
		}
	}

	private static boolean recoverPending(Path gameDirectory, DataRootResolver.Location dataLocation, RunLog log) throws IOException, InterruptedException {
		UpdateCommit commits = new UpdateCommit(ClientStorage.open(gameDirectory));
		long backoff = UpdateRecovery.INITIAL_BACKOFF_MILLIS;
		for (int attempt = 1;; attempt++) {
			boolean selfUpdateRecovered = recoverSelfUpdate(gameDirectory, dataLocation, log);
			UpdateCommit.Outcome outcome = commits.run(new UpdateCommit.Recover(null));
			boolean done = outcome.settled();
			if (done && selfUpdateRecovered) {
				log.narrate("Pending update transaction recovered on attempt " + attempt);
				return false;
			}
			if (!done && outcome instanceof UpdateCommit.Blocked blocked) {
				log.detail("Update recovery attempt " + attempt + " failed: status " + outcome.status() + ", operation " + blocked.operation() + ", blocked path "
						+ blocked.blockedPath() + ", message " + blocked.message());
			} else if (done) {
				log.detail("Update transaction recovered on attempt " + attempt + ", but the self-update swap is still pending");
			}
			if (outcome.status() == UpdateTransaction.Status.REPLAN_REQUIRED || attempt >= UpdateRecovery.MAX_ATTEMPTS) {
				log.fail("Update helper gave up; the transaction stays pending and the next game launch will retry it");
				return true;
			}
			Thread.sleep(backoff);
			backoff = Math.min(UpdateRecovery.MAX_BACKOFF_MILLIS, backoff * 2);
		}
	}

	private static FileLock tryLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		} catch (OverlappingFileLockException e) {
			return null;
		}
	}

	/** Waits until the launching game process has exited, or returns immediately when that process is already gone or the pid was reused. */
	private static void waitForGameExit(long parentPid, Long parentStartEpochMilli, RunLog log) {
		ProcessHandle parent = ProcessHandle.of(parentPid).orElse(null);
		if (parent == null) {
			log.narrate("Game process " + parentPid + " is already gone");
			return;
		}
		if (parentGone(parent, parentStartEpochMilli)) {
			log.narrate("Game process " + parentPid + " was reused by another process; starting recovery");
			return;
		}
		log.narrate("Waiting for the game process " + parentPid + " to exit");
		parent.onExit().join();
	}

	/** True when {@code parent} is not the process that spawned this helper. Empty startInstant cannot disprove identity, so that handle is still treated as the parent. */
	static boolean parentGone(ProcessHandle parent, Long parentStartEpochMilli) {
		if (parentStartEpochMilli == null) return false;
		return parent.info().startInstant().map(instant -> instant.toEpochMilli() != parentStartEpochMilli).orElse(false);
	}

	private static Long parentStartEpochMilli(String[] arguments) throws IOException {
		if (arguments.length < 3 || arguments[2].isBlank() || arguments[2].equals("-")) return null;
		try {
			return Long.parseLong(arguments[2]);
		} catch (NumberFormatException e) {
			throw new IOException("Invalid parent start epoch milli: " + arguments[2]);
		}
	}

	/**
	 * The environment role the launching game process resolved its data root with, so the helper recovers through the
	 * same root the planner planned into. Blank when the launcher did not know its role either; those processes share
	 * the roleless client selection anyway.
	 */
	private static LoaderManagerService.EnvironmentType environment(String[] arguments) throws IOException {
		if (arguments.length < 2 || arguments[1].isBlank()) return null;
		try {
			return LoaderManagerService.EnvironmentType.valueOf(arguments[1]);
		} catch (IllegalArgumentException e) {
			throw new IOException("Unknown launcher environment: " + arguments[1]);
		}
	}

	/**
	 * Narrates on stdout because the helper runs on a bare log4j default config whose root level would drop info lines; the launcher captures this stream into last-helper-run.log. Each line is timestamped so runs
	 * accumulated in that file stay individually readable.
	 */
	private static void log(String message) {
		System.out.println("[" + LocalDateTime.now().format(TIMESTAMP) + "] [AutoModpack update helper] " + message);
	}

	private static void writeStatus(RunLog log, long start) {
		try {
			DetachedUpdateHelper.writeRunStatus(GameDirectory.current().resolve(HELPER_STATUS_FILE).toAbsolutePath().normalize(), log.failed(), seconds(System.nanoTime() - start), log.details());
		} catch (IOException e) {
			log.narrate("Could not write helper status: " + e);
		}
	}

	/** One receipt line so the helper log identifies its run: version, parent, role, and the roots it recovers through. */
	private static void logStart(long parentPid, LoaderManagerService.EnvironmentType environment, Path gameDirectory, DataRootResolver.Location dataLocation, RunLog log) {
		String version = "unknown";
		Path helperJar = null;
		try {
			helperJar = JarUtils.getJarPath(UpdateHelperMain.class);
			String found = FileInspection.getModVersion(helperJar);
			if (found != null && !found.isBlank()) version = found;
		} catch (RuntimeException ignored) {
		}
		log.narrate("Update helper " + version + " starting; parent game process " + parentPid + ", environment " + (environment == null ? "unknown" : environment.name()) + ", game directory " + gameDirectory
				+ ", data root "
				+ dataLocation.root()
				+ (helperJar == null ? "" : ", running from " + helperJar));
	}

	private static boolean recoverSelfUpdate(Path gameDirectory, DataRootResolver.Location dataLocation, RunLog log) {
		try {
			SelfUpdateSwap.recover(gameDirectory, dataLocation);
			return true;
		} catch (IOException failure) {
			log.detail("Self-update recovery failed: " + failure);
			failure.printStackTrace();
			return false;
		}
	}

	/** Collects only failure details for the status document; narration stays in the helper log. */
	private static final class RunLog {
		private final List<String> details = new ArrayList<>();
		private boolean failed;

		void detail(String message) {
			details.add(message);
			log(message);
		}

		void fail(String message) {
			failed = true;
			detail(message);
		}

		void narrate(String message) {
			log(message);
		}

		boolean failed() {
			return failed;
		}

		List<String> details() {
			return details;
		}
	}
}
