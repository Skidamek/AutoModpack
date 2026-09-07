package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Filesystem publication primitives whose failure modes preserve the power-loss contract. */
public final class DurableFiles {
	private DurableFiles() {}

	/**
	 * Replaces one file with a same-filesystem temporary using an atomic directory-entry update.
	 * AutoModpack deliberately refuses filesystems without this primitive: Java has no portable
	 * fallback that guarantees the old or new complete file after power loss.
	 */
	public static void replace(Path temporary, Path target) throws IOException {
		Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
			try {
				replace(temporary, target);
			} catch (AtomicMoveNotSupportedException e) {
				throw new IOException("The filesystem cannot durably replace " + target + "; use a major local filesystem with atomic rename support", e);
			}
			FileTrees.forceDirectory(parent);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
