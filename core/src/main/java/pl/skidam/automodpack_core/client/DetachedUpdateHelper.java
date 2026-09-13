package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.DATA_ROOT_PROPERTY;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_DIR;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LEASE_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LOG_FILE;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

import com.google.gson.Gson;

import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.PlatformUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;

public final class DetachedUpdateHelper {
	private static final String HELPER_MAIN = UpdateHelperMain.class.getName();
	// Receipt: the helper holds the lease for its parent-exit wait plus a retry budget of ~82.5s of sleeps
	// (UpdateHelperMain) and per-attempt IO, so a helper that is converging frees the lease well inside it.
	// Past this wait the helper is stuck on a game process that will not exit, and hanging this boot behind
	// it serves nobody - the deferred recovery path proceeds without it and the helper keeps working alone.
	private static final Duration HELPER_LEASE_WAIT = Duration.ofMinutes(3);
	// A released lease is noticed within half a second, the helper's own initial backoff step.
	private static final long LEASE_POLL_MILLIS = 500;

	private DetachedUpdateHelper() {}

	/** The helper hosts instance-level machinery, so its jars live beside the instance state, outside any role tree. */
	public static Path helperDirectory() {
		return GameDirectory.current().resolve(HELPER_DIR).toAbsolutePath().normalize();
	}

	public static void launch() throws IOException {
		Path sourceJar = THIS_MOD_JAR.toAbsolutePath().normalize();
		if (!Files.isRegularFile(sourceJar)) throw new IOException("Runnable AutoModpack JAR is missing: " + sourceJar);
		Path absoluteHelperDirectory = helperDirectory();
		Files.createDirectories(absoluteHelperDirectory);

		long size = Files.size(sourceJar);
		String hash = FileIntegrity.identityHash(sourceJar, null);
		if (hash == null) throw new IOException("Cannot hash the runnable AutoModpack JAR");
		Path helperJar = absoluteHelperDirectory.resolve("automodpack-update-helper-" + UUID.randomUUID() + ".jar");
		VerifiedFileTransfer.copyAtomic(sourceJar, helperJar, size, hash);

		Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS ? "java.exe" : "java").toAbsolutePath().normalize();
		if (!Files.isRegularFile(javaExecutable)) throw new IOException("Java executable is missing: " + javaExecutable);
		String classpath = String.join(File.pathSeparator, helperJar.toString(), runtimeDependency(Gson.class).toString(), runtimeDependency(LogManager.class).toString(),
				runtimeDependency(LoggerContext.class).toString());
		// The game process exits right after this launch, so inherited streams would die with it; the helper's story must outlive the game in a file.
		// Append: a second helper that loses the lease still starts with this redirect already open, and must not truncate the running helper's log.
		Path helperLog = GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize();
		// The helper has no loader service. Pin this process's already-resolved root as a JVM property before the main class; configuredRoot prefers it over env and role defaults.
		String environment = LOADER_MANAGER == null || LOADER_MANAGER.getEnvironmentType() == null ? "" : LOADER_MANAGER.getEnvironmentType().name();
		DataRootResolver.Location dataLocation = DataRootResolver.resolve(GameDirectory.current(), LOADER_MANAGER == null ? null : LOADER_MANAGER.getEnvironmentType());
		new ProcessBuilder(javaExecutable.toString(), "-D" + DATA_ROOT_PROPERTY + "=" + dataLocation.root(), "-cp", classpath, HELPER_MAIN, Long.toString(ProcessHandle.current().pid()), environment)
				.directory(GameDirectory.current().toFile())
				.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(helperLog.toFile())).start();
		LOGGER.info("Launched detached update helper for the latest pending transaction from {} (environment {}); its output goes to {}", helperJar, environment.isBlank() ? "unknown" : environment, helperLog);
	}

	/**
	 * True after waiting out a helper that already held the lease, so the caller should retry recovery once.
	 * False when none was running, or when it still held the lease after {@link #HELPER_LEASE_WAIT} and this
	 * boot proceeds without it - the running helper keeps its lease and finishes, or gives up, on its own.
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
			LOGGER.info("Waiting for the detached update helper to finish");
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

	public static void cleanupOldHelperJars() {
		cleanupOldHelperJars(helperDirectory());
	}

	private static void cleanupOldHelperJars(Path directory) {
		if (!Files.isDirectory(directory)) return;
		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.filter(path -> path.getFileName().toString().startsWith("automodpack-update-helper-")
					&& JarUtils.hasJarExtension(path)).toList()) {
				try {
					Files.deleteIfExists(file);
				} catch (IOException e) {
					LOGGER.debug("Helper JAR is still in use: {}", file);
				}
			}
		} catch (IOException e) {
			LOGGER.debug("Failed to clean old update-helper JARs", e);
		}
	}

	private static Path runtimeDependency(Class<?> type) throws IOException {
		Path path;
		try {
			path = JarUtils.getJarPath(type).toAbsolutePath().normalize();
		} catch (RuntimeException e) {
			throw new IOException("Cannot locate helper runtime dependency " + type.getName(), e);
		}
		if (!Files.isRegularFile(path) && !Files.isDirectory(path)) throw new IOException("Helper runtime dependency is unavailable: " + path);
		return path;
	}

}
