package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** The one way to hold a cross-process lock: a lock file opened CREATE+WRITE and locked for the whole operation. */
public final class FileLocks {
	private FileLocks() {}

	@FunctionalInterface
	public interface LockedOperation<T> {
		T run() throws IOException;
	}

	public static <T> T withLock(Path lockPath, LockedOperation<T> operation) throws IOException {
		Files.createDirectories(lockPath.getParent());
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
			return operation.run();
		}
	}

	/** Same, with one extra open option for lock files that must not follow symbolic links. */
	public static <T> T withLock(Path lockPath, OpenOption option, LockedOperation<T> operation) throws IOException {
		Files.createDirectories(lockPath.getParent());
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, option); FileLock ignored = channel.lock()) {
			return operation.run();
		}
	}
}
