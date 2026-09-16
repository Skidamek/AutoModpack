package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes every durable mutation of one game directory across threads and processes. */
public final class ClientStorageMutation {
	private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

	private ClientStorageMutation() {}

	public static <T> T run(ClientStorage storage, Operation<T> operation) throws IOException {
		Path path = storage.mutationLockFile();
		ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(path, ignored -> new ReentrantLock());
		jvmLock.lock();
		try {
			if (jvmLock.getHoldCount() > 1) return operation.run();
			try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); FileLock ignored = channel.lock()) {
				return operation.run();
			}
		} finally {
			jvmLock.unlock();
		}
	}

	@FunctionalInterface
	public interface Operation<T> {
		T run() throws IOException;
	}
}
