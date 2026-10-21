package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.THIS_MOD_JAR;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_DIR;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;

/**
 * The copy of the loaded AutoModpack jar that detached processes run from. The game may delete the jar in
 * {@code mods/} during a self-update, so every helper and restart window uses this copy instead. Each copy has a
 * sidecar lock file; a detached process holds it shared for its whole life, and boot cleanup takes it exclusively,
 * so a jar that is still running can never be deleted.
 */
public final class RuntimeJar {
	private static final Logger LOGGER = LogManager.getLogger(RuntimeJar.class);
	private static final String PREFIX = "automodpack-runtime-";
	private static final String LOCK_SUFFIX = ".lock";
	private static final Map<Path, Lease> HELD = new ConcurrentHashMap<>();
	private static volatile Path current;

	private RuntimeJar() {}

	/** Materializes this process's runtime jar and cleans up old copies. Called once at boot, before anything spawns. */
	public static void prepareForBoot() {
		Path directory = defaultDirectory();
		try {
			Path jar = ensure(THIS_MOD_JAR, directory);
			current = jar;
			cleanup(directory, jar);
		} catch (IOException e) {
			LOGGER.error("Could not prepare the AutoModpack runtime jar; detached processes will retry when needed", e);
		}
	}

	/** The runtime jar for this process, copying it again if a previous copy disappeared. */
	public static Path current() throws IOException {
		Path jar = current;
		if (jar != null && Files.isRegularFile(jar)) return jar;
		synchronized (RuntimeJar.class) {
			if (current != null && Files.isRegularFile(current)) return current;
			current = ensure(THIS_MOD_JAR, defaultDirectory());
			return current;
		}
	}

	/** Holds the current runtime jar shared for the rest of this process's life. */
	public static void holdLease() {
		try {
			holdLease(current());
		} catch (IOException e) {
			throw new IllegalStateException("Cannot lease the AutoModpack runtime jar", e);
		}
	}

	/** Holds {@code jar} shared for the rest of this process's life. Repeated calls for the same jar are no-ops. */
	public static void holdLease(Path jar) {
		Path lockFile = lockFile(jar);
		synchronized (HELD) {
			if (HELD.containsKey(lockFile)) return;
			FileChannel channel = null;
			try {
				channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
				FileLock lock = channel.lock(0, Long.MAX_VALUE, true);
				HELD.put(lockFile, new Lease(channel, lock));
			} catch (IOException e) {
				close(channel);
				throw new IllegalStateException("Cannot lease the AutoModpack runtime jar " + jar, e);
			}
		}
	}

	static Path ensure(Path source, Path directory) throws IOException {
		Path sourceJar = source.toAbsolutePath().normalize();
		if (!Files.isRegularFile(sourceJar)) throw new IOException("Runnable AutoModpack JAR is missing: " + sourceJar);
		long size = Files.size(sourceJar);
		String hash = FileIntegrity.identityHash(sourceJar, null);
		if (hash == null) throw new IOException("Cannot hash the runnable AutoModpack JAR");
		Path absoluteDirectory = directory.toAbsolutePath().normalize();
		Files.createDirectories(absoluteDirectory);
		Path runtimeJar = absoluteDirectory.resolve(PREFIX + hash + ".jar");
		if (!FileIntegrity.matches(runtimeJar, size, hash)) VerifiedFileTransfer.copyAtomic(sourceJar, runtimeJar, size, hash);
		return runtimeJar;
	}

	static void cleanup(Path directory, Path currentJar) {
		Path absoluteDirectory = directory.toAbsolutePath().normalize();
		Path keep = currentJar.toAbsolutePath().normalize();
		if (!Files.isDirectory(absoluteDirectory)) return;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(absoluteDirectory, PREFIX + "*.jar")) {
			for (Path candidate : files) {
				if (candidate.toAbsolutePath().normalize().equals(keep)) continue;
				Path lockFile = lockFile(candidate);
				if (!Files.exists(lockFile)) {
					Files.deleteIfExists(candidate);
					continue;
				}
				try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
						FileLock ignored = channel.tryLock(0, Long.MAX_VALUE, false)) {
					if (ignored == null) {
						LOGGER.debug("Keeping the leased runtime jar {}", candidate);
						continue;
					}
					Files.deleteIfExists(candidate);
					Files.deleteIfExists(lockFile);
				} catch (OverlappingFileLockException e) {
					LOGGER.debug("Keeping the runtime jar leased by this process {}", candidate);
				} catch (IOException e) {
					LOGGER.debug("Could not clean up the runtime jar {}", candidate, e);
				}
			}
		} catch (IOException e) {
			LOGGER.debug("Could not clean up runtime jars in {}", absoluteDirectory, e);
		}
	}

	static Path lockFile(Path jar) {
		Path absolute = jar.toAbsolutePath().normalize();
		return absolute.resolveSibling(absolute.getFileName().toString() + LOCK_SUFFIX);
	}

	private static Path defaultDirectory() {
		return GameDirectory.current().resolve(HELPER_DIR).toAbsolutePath().normalize();
	}

	private static void close(FileChannel channel) {
		if (channel == null) return;
		try {
			channel.close();
		} catch (IOException ignored) {
		}
	}

	private record Lease(FileChannel channel, FileLock lock) {}
}
