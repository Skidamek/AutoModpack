package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * What one filesystem's force costs, measured once per {@link FileStore} and shared by every caller that has to decide
 * between overlapping IO and keeping it in order. A 64 KiB fsync is ~1 ms on NVMe and tens of milliseconds on a
 * spinning disk, so eight milliseconds separates a store that can absorb interleaved syncs from one where doing so
 * would make the user wait longer than the join-time batch would.
 */
public final class ForceProfile {
	private static final Logger LOGGER = LogManager.getLogger(ForceProfile.class);

	// Measured on the benchmark host: a 64 KiB force is ~18 ms warm, ~55 ms cold on the spinning disk, and ~1 ms on
	// NVMe; 1 MiB is ~31 ms vs ~1.5 ms. Eight milliseconds separates the two without treating a loaded SSD as rotational.
	private static final int PROBE_BYTES = 64 * 1024;
	private static final long SLOW_FORCE_NANOS = TimeUnit.MILLISECONDS.toNanos(8);
	private static final ConcurrentHashMap<FileStore, Boolean> OVERLAPPED_FORCE_ALLOWED = new ConcurrentHashMap<>();

	private ForceProfile() {}

	/** The store holding {@code path}; the path itself need not exist yet. */
	public static FileStore storeOf(Path path) throws IOException {
		Path current = path.toAbsolutePath().normalize();
		while (current != null && !Files.exists(current)) current = current.getParent();
		if (current == null) throw new IOException("No existing ancestor for " + path);
		return Files.getFileStore(current);
	}

	/**
	 * Whether forces on this path's store are cheap enough to overlap with other work. One real force decides it; the
	 * verdict is cached per store, so a download pass and an install pass pay it once.
	 */
	public static boolean allowsOverlappedForce(Path directory) {
		FileStore store;
		try {
			Files.createDirectories(directory);
			store = storeOf(directory);
			Boolean known = OVERLAPPED_FORCE_ALLOWED.get(store);
			if (known != null) return known;
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("Could not inspect the store of {}; work stays in its ordered path", directory, e);
			return false;
		}
		Path probe = null;
		try {
			probe = Files.createTempFile(directory, ".force-probe-", DurableFiles.TEMPORARY_SUFFIX);
			byte[] bytes = new byte[PROBE_BYTES];
			new Random(1).nextBytes(bytes);
			Files.write(probe, bytes);
			try (FileChannel channel = FileChannel.open(probe, StandardOpenOption.WRITE)) {
				long start = System.nanoTime();
				channel.force(true);
				long elapsed = System.nanoTime() - start;
				boolean allowed = elapsed <= SLOW_FORCE_NANOS;
				OVERLAPPED_FORCE_ALLOWED.put(store, allowed);
				if (!allowed) LOGGER.info("Force probe took {} ms; this store keeps IO in its ordered path", TimeUnit.NANOSECONDS.toMillis(elapsed));
				return allowed;
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("Could not measure force latency; work stays in its ordered path", e);
			return false;
		} finally {
			if (probe != null) try {
				Files.deleteIfExists(probe);
			} catch (IOException ignored) {
			}
		}
	}
}
