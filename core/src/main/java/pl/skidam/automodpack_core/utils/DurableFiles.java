package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Filesystem publication primitives with defined power-loss contracts: durable writes force, volatile writes only promise atomic-while-running. */
public final class DurableFiles {
	private static final AtomicBoolean NON_ATOMIC_RENAME_WARNED = new AtomicBoolean();

	private DurableFiles() {}

	/**
	 * Replaces one file with a same-filesystem temporary. Atomic rename is the primary path; on a filesystem without
	 * it, the plain fallback publishes the caller-synced temporary with a normal move plus a parent directory force.
	 * A power cut in that degraded mode can lose the new file, but the old one stays whole.
	 */
	public static void replace(Path temporary, Path target) throws IOException {
		try {
			replaceAtomically(temporary, target);
		} catch (AtomicMoveNotSupportedException e) {
			replaceWithoutAtomicRename(temporary, target, e);
		}
	}

	/** Atomic rename only; for callers such as cross-filesystem promotion that own a stronger verified fallback. */
	static void replaceAtomically(Path temporary, Path target) throws IOException {
		Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	/** The caller must have forced the temporary to stable storage; this publishes it and forces the directory entry. */
	static void replaceWithoutAtomicRename(Path temporary, Path target, AtomicMoveNotSupportedException cause) throws IOException {
		if (NON_ATOMIC_RENAME_WARNED.compareAndSet(false, true))
			LOGGER.warn("The filesystem hosting {} lacks atomic rename; falling back to plain moves for durable replacement, where a power cut can lose the newest file but never corrupts the previous one",
					target.toAbsolutePath().normalize(), cause);
		Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		Path parent = target.toAbsolutePath().normalize().getParent();
		if (parent != null) FileTrees.forceDirectory(parent);
	}

	/**
	 * Writes bytes to a fresh same-filesystem temporary, forces it to stable storage, then publishes it with {@link #replace}
	 * and forces the parent directory entry. The temporary is always removed, even on failure.
	 */
	public static void writeAtomic(Path target, byte[] bytes) throws IOException {
		Path parent = target.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException("Path has no parent: " + target);
		Files.createDirectories(parent);

		Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			replace(temporary, target);
			FileTrees.forceDirectory(parent);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Writes bytes to a fresh same-filesystem temporary and publishes it with {@link #replace}, skipping every
	 * force. The publication is atomic while the system runs, so a reader never sees torn content, but a power
	 * cut may lose the newest file or keep the old one; for rebuildable data such as cache records that only
	 * costs a recompute.
	 */
	public static void writeVolatile(Path target, byte[] bytes) throws IOException {
		Path parent = target.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException("Path has no parent: " + target);
		Files.createDirectories(parent);

		Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try {
			Files.write(temporary, bytes);
			replace(temporary, target);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
