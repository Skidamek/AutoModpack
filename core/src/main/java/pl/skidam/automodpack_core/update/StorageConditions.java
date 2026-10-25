package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.util.Locale;

/**
 * Storage conditions no waiting fixes: a full disk, a read-only filesystem, an operation the filesystem does not
 * support, a failing device. Each surfaces as the same generic {@link FileSystemException} a held file does, so
 * {@link UpdateCommit#isLockFailure} asks here first: a condition is a failure with a clear message, never a deferral
 * that restarts into the same wall.
 *
 * <p>
 * The decision does not depend on the message, which the OS localizes (strerror follows the JVM locale, FormatMessage
 * the Windows UI language): the store the failing path lives on is asked, which no locale changes. It is full when its
 * usable space is below the bytes the failing write needed plus {@link #MARGIN_BYTES} (filesystems keep reserves and
 * allocate whole clusters, so a write fails before the counter reaches zero), and read-only when it says so. Exception
 * types the JDK maps one to one come next; the exact C and English spellings are only a last hint for what no store
 * reports (quota, unsupported operation, I/O error).
 */
final class StorageConditions {
	private StorageConditions() {}

	static final long MARGIN_BYTES = 1L << 20;

	/** A failure already classified where the size of the failing write was known; the commit reports its condition. */
	static final class ConditionException extends IOException {
		private final String condition;

		ConditionException(String condition, IOException cause) {
			super(describe(condition, cause), cause);
			this.condition = condition;
		}

		String condition() {
			return condition;
		}
	}

	/** Rethrows {@code failure} as the condition it is for a write of {@code requiredBytes}, or returns it unchanged. */
	static IOException classified(IOException failure, long requiredBytes) {
		if (failure instanceof ConditionException) return failure;
		String condition = condition(failure, requiredBytes);
		return condition == null ? failure : new ConditionException(condition, failure);
	}

	/** What the store a path lives on reports. */
	record Store(boolean readOnly, long usableSpace) {}

	/** Seam over the store query (one lookup per classification), so a test can stand in a nearly full or read-only volume. */
	@FunctionalInterface
	interface Stores {
		Stores DEFAULT = existing -> {
			FileStore store = Files.getFileStore(existing);
			return new Store(store.isReadOnly(), store.getUsableSpace());
		};

		Store query(Path existing) throws IOException;
	}

	static volatile Stores stores = Stores.DEFAULT;

	/** Exact reasons (C locale strerror, English FormatMessage) for what the store cannot report. Hints only. */
	private static final String[][] HINTS = {
			{"disk quota exceeded", "the disk quota is exhausted"},
			{"operation not supported", "the filesystem does not support a required operation"},
			{"function not implemented", "the filesystem does not support a required operation"},
			{"the request is not supported", "the filesystem does not support a required operation"},
			{"input/output error", "the storage device reported an I/O error"},
			{"file too large", "the filesystem cannot hold a file this large"},
			{"no space left on device", "the disk is full"},
			{"there is not enough space on the disk", "the disk is full"},
			{"read-only file system", "the filesystem is read-only"},
			{"the media is write protected", "the filesystem is read-only"},
	};

	/** {@link #condition(Throwable, long)} for a failure whose write size is unknown. */
	static String condition(Throwable failure) {
		return condition(failure, 0);
	}

	/** What no wait fixes about this failure of a write of {@code requiredBytes}, or null when it may be another process holding a path. */
	static String condition(Throwable failure, long requiredBytes) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof ConditionException classified) return classified.condition();
			if (current instanceof UnsupportedOperationException) return "the filesystem does not support a required operation";
			if (current instanceof ReadOnlyFileSystemException) return "the filesystem is read-only";
			if (!(current instanceof FileSystemException fileSystem)) continue;
			String store = storeCondition(fileSystem.getFile(), Math.max(0, requiredBytes));
			if (store == null) store = storeCondition(fileSystem.getOtherFile(), Math.max(0, requiredBytes));
			if (store != null) return store;
			String reason = fileSystem.getReason();
			if (reason == null) continue;
			String lower = reason.toLowerCase(Locale.ROOT).strip();
			for (String[] hint : HINTS) if (lower.contains(hint[0])) return hint[1];
		}
		return null;
	}

	/** The message a failed apply records for a condition. */
	static String describe(String condition, IOException failure) {
		return "The update cannot continue because " + condition + ": " + failure.getMessage();
	}

	private static String storeCondition(String file, long requiredBytes) {
		if (file == null || file.isBlank()) return null;
		try {
			Path path = Path.of(file).toAbsolutePath();
			while (path != null && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) path = path.getParent();
			if (path == null) return null;
			Store store = stores.query(path);
			if (store.readOnly()) return "the filesystem is read-only";
			long usable = store.usableSpace();
			if (usable < requiredBytes + MARGIN_BYTES) return "the disk is full (" + usable / 1024 + " KiB free, " + (requiredBytes + MARGIN_BYTES) / 1024 + " KiB needed)";
			return null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
