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
 * What one filesystem's force costs. Every caller that has to choose between overlapping IO and keeping it in order gets
 * the measurement and compares it with the bar its own trade-off uses; the probe runs once per {@link FileStore} and every
 * later caller pays a map lookup. {@link #isNotRotationalStore} is the one shared verdict, because "is this store
 * rotational" is the same question for every caller; anything narrower belongs to the caller.
 */
public final class ForceProfile {
	private static final Logger LOGGER = LogManager.getLogger(ForceProfile.class);

	// Measured on the benchmark host: a 64 KiB force is ~18 ms warm, ~55 ms cold on the spinning disk, and ~1 ms on
	// NVMe; 1 MiB is ~31 ms vs ~1.5 ms. Eight milliseconds separates the two without treating a loaded SSD as rotational.
	private static final int PROBE_BYTES = 64 * 1024;
	// The separation between a rotational store and a solid-state one; a caller that owns a narrower trade-off compares
	// forceNanos with its own bar instead. Bounded by the number of stores because FileStore overrides equals per device.
	private static final long NOT_ROTATIONAL_FORCE_NANOS = TimeUnit.MILLISECONDS.toNanos(8);
	private static final ConcurrentHashMap<FileStore, Long> FORCE_NANOS = new ConcurrentHashMap<>();

	/** No measurement: the store could not be inspected or probed, so none of its forces is proven cheap. */
	public static final long UNMEASURED = Long.MAX_VALUE;

	private ForceProfile() {}

	/** The store holding {@code path}; the path itself need not exist yet. */
	public static FileStore storeOf(Path path) throws IOException {
		Path current = path.toAbsolutePath().normalize();
		while (current != null && !Files.exists(current)) current = current.getParent();
		if (current == null) throw new IOException("No existing ancestor for " + path);
		return Files.getFileStore(current);
	}

	/**
	 * What one 64 KiB force costs on this path's store, in nanoseconds, or {@link #UNMEASURED} when the store cannot be
	 * inspected or the probe fails. One real force decides it and the answer is cached per store, so a download pass and
	 * an install pass share one probe.
	 */
	public static long forceNanos(Path directory) {
		FileStore store;
		try {
			Files.createDirectories(directory);
			store = storeOf(directory);
			Long known = FORCE_NANOS.get(store);
			if (known != null) return known;
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("Could not inspect the store of {}; its force cost stays unknown", directory, e);
			return UNMEASURED;
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
				FORCE_NANOS.put(store, elapsed);
				return elapsed;
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("Could not measure force latency on {}", directory, e);
			return UNMEASURED;
		} finally {
			if (probe != null) try {
				Files.deleteIfExists(probe);
			} catch (IOException ignored) {
			}
		}
	}

	/** Whether this path's store is solid-state rather than rotational; {@link #forceNanos} carries the measurement. */
	public static boolean isNotRotationalStore(Path directory) {
		long nanos = forceNanos(directory);
		if (nanos <= NOT_ROTATIONAL_FORCE_NANOS) return true;
		if (nanos != UNMEASURED) LOGGER.info("Force probe took {} ms; this store is treated as rotational", TimeUnit.NANOSECONDS.toMillis(nanos));
		return false;
	}
}
