package pl.skidam.automodpack_core.security;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** A JVM-local mutex plus a bounded OS file lock. */
public final class SharedSecurityFileLock {
    @FunctionalInterface
    public interface Action<T> {
        T run() throws Exception;
    }

    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private SharedSecurityFileLock() {
    }

    public static <T> T withExclusiveLock(Path lockPath, long timeoutMs, Action<T> action) throws Exception {
        if (lockPath == null || action == null) {
            throw new IllegalArgumentException("Lock path and action are required");
        }
        Path normalized = lockPath.toAbsolutePath().normalize();
        rejectSymbolicLinkComponents(normalized);
        long deadline = deadlineAfterMillis(timeoutMs);
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(normalized, ignored -> new ReentrantLock(true));
        boolean localLocked = false;
        try {
            long localRemaining = remainingNanos(deadline);
            localLocked = localRemaining > 0 && jvmLock.tryLock(localRemaining, TimeUnit.NANOSECONDS);
            if (!localLocked) {
                throw new IOException("Timed out acquiring shared security lock: " + normalized);
            }

            Path parent = normalized.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            rejectSymbolicLinkComponents(normalized);

            try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock processLock = acquireProcessLock(channel, deadline, normalized);
                try (FileLock ignored = processLock) {
                    return action.run();
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring shared security lock: " + normalized, exception);
        } finally {
            if (localLocked) {
                jvmLock.unlock();
            }
        }
    }

    private static long deadlineAfterMillis(long timeoutMs) {
        long boundedTimeoutMs = Math.max(1, timeoutMs);
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(boundedTimeoutMs);
        long now = System.nanoTime();
        long deadline = now + timeoutNanos;
        if (deadline < now) {
            return Long.MAX_VALUE;
        }
        return deadline;
    }

    private static long remainingNanos(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining > 0 ? remaining : 0;
    }

    private static void rejectSymbolicLinkComponents(Path path) throws IOException {
        Path current = path;
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Shared security lock path cannot contain a symbolic link: " + current);
            }
            current = current.getParent();
        }
    }

    private static FileLock acquireProcessLock(FileChannel channel, long deadline, Path path) throws IOException, InterruptedException {
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // Another thread/process in this JVM owns the OS lock. The JVM mutex normally prevents this,
                // but this remains defensive for aliases and callers using a non-canonical path.
            }

            long remainingNanos = remainingNanos(deadline);
            if (remainingNanos <= 0) {
                throw new IOException("Timed out acquiring shared security lock: " + path);
            }
            long sleepMillis = Math.min(25, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            Thread.sleep(sleepMillis);
        }
    }
}
