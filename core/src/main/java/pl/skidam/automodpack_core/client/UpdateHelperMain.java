package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LEASE_FILE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.SelfUpdateSwap;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.JarUtils;

public final class UpdateHelperMain {
	// Receipt: the launcher exits within seconds of spawning the helper (popup OK click), and even a user who
	// walks away comes back well inside 15 minutes. A parent still alive past that is not coming back - a
	// reused PID of some immortal process or a hung game - and holding the lease for it would park every
	// future boot's recovery wait. Giving up leaves the transaction pending; the next game launch retries it.
	private static final long PARENT_EXIT_TIMEOUT_MILLIS = Duration.ofMinutes(15).toMillis();
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(Locale.ROOT);

	private UpdateHelperMain() {}

	public static void main(String[] arguments) {
		int exitCode = run(arguments);
		System.out.flush();
		if (exitCode != 0) System.exit(exitCode);
	}

	static int run(String[] arguments) {
		long start = System.nanoTime();
		try {
			return runOnce(arguments);
		} catch (Exception failure) {
			log("Fatal helper failure: " + failure);
			failure.printStackTrace();
			return 1;
		} finally {
			log("Update helper run ended after " + seconds(System.nanoTime() - start));
		}
	}

	private static String seconds(long nanos) {
		return String.format(Locale.ROOT, "%.1fs", nanos / 1_000_000_000.0);
	}

	private static int runOnce(String[] arguments) throws IOException, InterruptedException {
		if (arguments.length < 1 || arguments.length > 2) throw new IOException("Expected parent PID and optional environment");
		long parentPid = Long.parseLong(arguments[0]);
		if (parentPid <= 0 || parentPid == ProcessHandle.current().pid()) throw new IOException("Invalid parent PID");
		LoaderManagerService.EnvironmentType environment = environment(arguments);

		Path gameDirectory = GameDirectory.current();
		DataRootResolver.Location dataLocation = DataRootResolver.resolve(gameDirectory, environment);
		logStart(parentPid, environment, gameDirectory, dataLocation);
		Path leaseFile = gameDirectory.resolve(HELPER_LEASE_FILE).toAbsolutePath().normalize();
		Files.createDirectories(leaseFile.getParent());
		try (FileChannel leaseChannel = FileChannel.open(leaseFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			FileLock lease;
			try {
				lease = leaseChannel.tryLock();
			} catch (OverlappingFileLockException e) {
				log("Another update helper already holds the lease; exiting");
				return 0;
			}
			if (lease == null) {
				log("Another update helper already holds the lease; exiting");
				return 0;
			}
			try (lease) {
				try {
					if (!waitForGameExit(parentPid)) return 1;

					UpdateTransactionExecutor executor = UpdateTransactionSupport.executor();
					long backoff = UpdateRecovery.INITIAL_BACKOFF_MILLIS;
					for (int attempt = 1;; attempt++) {
						boolean selfUpdateRecovered = recoverSelfUpdate(gameDirectory, dataLocation);
						UpdateTransactionExecutor.Execution execution = executor.recoverLatest();
						if (execution.success() && selfUpdateRecovered) {
							log("Pending update transaction recovered on attempt " + attempt);
							return 0;
						}
						if (!execution.success()) {
							log("Update recovery attempt " + attempt + " failed: status " + execution.status() + ", operation " + execution.operation() + ", blocked path "
									+ execution.blockedPath() + ", message " + execution.message());
						} else {
							// A pending swap record left behind would name an object the next boot's recovery still needs;
							// exiting success here would drop it on the floor.
							log("Update transaction recovered on attempt " + attempt + ", but the self-update swap is still pending");
						}
						if (execution.replanRequired() || attempt >= UpdateRecovery.MAX_ATTEMPTS) {
							log("Update helper gave up; the transaction stays pending and the next game launch will retry it");
							return 1;
						}
						Thread.sleep(backoff);
						backoff = Math.min(UpdateRecovery.MAX_BACKOFF_MILLIS, backoff * 2);
					}
				} finally {
					DetachedUpdateHelper.cleanupOldHelperJars();
				}
			}
		}
	}

	/**
	 * Bounded wait for the launching game to exit, since recovery while it runs only fails on the locks it
	 * holds. False when the parent outlived {@link #PARENT_EXIT_TIMEOUT_MILLIS} or the wait failed; the
	 * transaction then stays pending and the next game launch retries it.
	 */
	private static boolean waitForGameExit(long parentPid) throws InterruptedException {
		ProcessHandle parent = ProcessHandle.of(parentPid).orElse(null);
		if (parent == null) return true;
		log("Waiting for the game process " + parentPid + " to exit");
		try {
			parent.onExit().get(PARENT_EXIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			return true;
		} catch (TimeoutException timeout) {
			log("The game process " + parentPid + " is still alive after " + PARENT_EXIT_TIMEOUT_MILLIS / 60000 + " minutes; giving up; the transaction stays pending and the next game launch will retry it");
			return false;
		} catch (ExecutionException failure) {
			log("Waiting for the game process " + parentPid + " failed: " + failure.getCause() + "; the transaction stays pending and the next game launch will retry it");
			return false;
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

	/** One receipt line so the helper log identifies its run: version, parent, role, and the roots it recovers through. */
	private static void logStart(long parentPid, LoaderManagerService.EnvironmentType environment, Path gameDirectory, DataRootResolver.Location dataLocation) {
		String version = "unknown";
		Path helperJar = null;
		try {
			helperJar = JarUtils.getJarPath(UpdateHelperMain.class);
			String found = FileInspection.getModVersion(helperJar);
			if (found != null && !found.isBlank()) version = found;
		} catch (RuntimeException ignored) {
		}
		log("Update helper " + version + " starting; parent game process " + parentPid + ", environment " + (environment == null ? "unknown" : environment.name()) + ", game directory " + gameDirectory + ", data root "
				+ dataLocation.root()
				+ (helperJar == null ? "" : ", running from " + helperJar));
	}

	private static boolean recoverSelfUpdate(Path gameDirectory, DataRootResolver.Location dataLocation) {
		try {
			SelfUpdateSwap.recover(gameDirectory, dataLocation);
			return true;
		} catch (IOException failure) {
			log("Self-update recovery failed: " + failure);
			failure.printStackTrace();
			return false;
		}
	}
}
