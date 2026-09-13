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

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.SelfUpdateSwap;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;

public final class UpdateHelperMain {
	// Receipts: in-repo, 11s of retries lost an on-access lock race. Microsoft Defender cloud block holds a file 10s by default,
	// extendable to 60s (Configure extended cloud check). Geometric 500ms→5s over 20 attempts sleeps ~82.5s, past that 60s cap
	// with slack for a local archive scan of one jar (Defender's "expensive file" log threshold is 3s; there is no documented RTP cap).
	private static final int MAX_ATTEMPTS = 20;
	private static final long INITIAL_BACKOFF_MILLIS = 500;
	private static final long MAX_BACKOFF_MILLIS = 5_000;

	private UpdateHelperMain() {}

	public static void main(String[] arguments) {
		int exitCode = run(arguments);
		if (exitCode != 0) System.exit(exitCode);
	}

	static int run(String[] arguments) {
		try {
			if (arguments.length < 1 || arguments.length > 2) throw new IOException("Expected parent PID and optional environment");
			long parentPid = Long.parseLong(arguments[0]);
			if (parentPid <= 0 || parentPid == ProcessHandle.current().pid()) throw new IOException("Invalid parent PID");
			LoaderManagerService.EnvironmentType environment = environment(arguments);

			Path gameDirectory = GameDirectory.current();
			DataRootResolver.Location dataLocation = DataRootResolver.resolve(gameDirectory, environment);
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
						ProcessHandle.of(parentPid).ifPresent(parent -> {
							log("Waiting for the game process " + parentPid + " to exit");
							parent.onExit().join();
						});

						UpdateTransactionExecutor executor = UpdateTransactionSupport.executor();
						long backoff = INITIAL_BACKOFF_MILLIS;
						for (int attempt = 1;; attempt++) {
							recoverSelfUpdate(gameDirectory, dataLocation);
							UpdateTransactionExecutor.Execution execution = executor.recoverLatest();
							if (execution.success()) {
								log("Pending update transaction recovered on attempt " + attempt);
								return 0;
							}
							log("Update recovery attempt " + attempt + " failed: status " + execution.status() + ", operation " + execution.operation() + ", blocked path " + execution.blockedPath()
									+ ", message " + execution.message());
							if (execution.replanRequired() || attempt >= MAX_ATTEMPTS) {
								log("Update helper gave up; the transaction stays pending and the next game launch will retry it");
								return 1;
							}
							Thread.sleep(backoff);
							backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
						}
					} finally {
						DetachedUpdateHelper.cleanupOldHelperJars();
					}
				}
			}
		} catch (Exception failure) {
			failure.printStackTrace();
			return 1;
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

	/** Narrates on stdout because the helper runs on a bare log4j default config whose root level would drop info lines; the launcher captures this stream into last-helper-run.log. */
	private static void log(String message) {
		System.out.println("[AutoModpack update helper] " + message);
	}

	private static boolean recoverSelfUpdate(Path gameDirectory, DataRootResolver.Location dataLocation) {
		try {
			SelfUpdateSwap.recover(gameDirectory, dataLocation);
			return true;
		} catch (IOException failure) {
			failure.printStackTrace();
			return false;
		}
	}
}
