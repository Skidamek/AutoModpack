package pl.skidam.automodpack_core.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

final class AtomicFileWriter {
	private AtomicFileWriter() {}

	static void write(Path path, byte[] bytes) throws IOException {
		Path parent = path.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException("Configuration path has no parent: " + path);
		Files.createDirectories(parent);

		Path temporary = parent.resolve("." + path.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				throw new IOException("Atomic configuration replacement is unsupported for " + path, e);
			}
			forceDirectory(parent);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void forceDirectory(Path directory) {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException | UnsupportedOperationException ignored) {
			// Directory fsync is unavailable on some supported filesystems.
		}
	}
}
