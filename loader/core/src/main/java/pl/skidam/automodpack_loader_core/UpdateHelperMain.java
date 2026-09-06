package pl.skidam.automodpack_loader_core;

import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LEASE_FILE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.SelfUpdateSwap;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;

public final class UpdateHelperMain {
	private static final int MAX_ATTEMPTS = 8;
	private static final long INITIAL_BACKOFF_MILLIS = 250;
	private static final long MAX_BACKOFF_MILLIS = 2_000;

	private UpdateHelperMain() {}

	public static void main(String[] arguments) {
		int exitCode = run(arguments);
		if (exitCode != 0) System.exit(exitCode);
	}

	static int run(String[] arguments) {
		try {
			if (arguments.length != 1) throw new IOException("Expected parent PID");
			long parentPid = Long.parseLong(arguments[0]);
			if (parentPid <= 0 || parentPid == ProcessHandle.current().pid()) throw new IOException("Invalid parent PID");

			Path gameDirectory = GameDirectory.current();
			DataRootResolver.Location dataLocation = DataRootResolver.resolve(gameDirectory);
			Path leaseFile = gameDirectory.resolve(HELPER_LEASE_FILE).toAbsolutePath().normalize();
			Files.createDirectories(leaseFile.getParent());
			try (FileChannel leaseChannel = FileChannel.open(leaseFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				FileLock lease;
				try {
					lease = leaseChannel.tryLock();
				} catch (OverlappingFileLockException e) {
					return 0;
				}
				if (lease == null) return 0;
				try (lease) {
					try {
						ProcessHandle.of(parentPid).ifPresent(parent -> parent.onExit().join());

						UpdateTransactionExecutor executor = UpdateTransactionSupport.executor();
						long backoff = INITIAL_BACKOFF_MILLIS;
						for (int attempt = 1;; attempt++) {
							boolean selfUpdateApplied = recoverSelfUpdate(gameDirectory, dataLocation);
							UpdateTransactionExecutor.Execution execution = executor.recoverLatest();
							if (selfUpdateApplied && execution.success()) return 0;
							if (execution.replanRequired() || attempt >= MAX_ATTEMPTS) return 1;
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
