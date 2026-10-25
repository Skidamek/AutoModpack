package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Storage conditions no waiting fixes: a full disk, a read-only filesystem, an operation the filesystem does not
 * support, a failing device. Each surfaces as the same generic {@link FileSystemException} a held file does, so
 * {@link UpdateCommit#isLockFailure} asks here first: a condition is a failure with a clear message, never a deferral
 * that restarts into the same wall.
 *
 * <p>
 * The kernel's reason text is localized, so the reason is read in the spellings the JDK reports for the C and English
 * locales (strerror, FormatMessage), and then confirmed against the file store itself, which no locale changes: a
 * store that reports itself read-only, or no usable space, is the condition whatever the message said.
 */
final class StorageConditions {
	private StorageConditions() {}

	private static final String[][] REASONS = {
			{"no space left on device", "the disk is full"},
			{"not enough space on the disk", "the disk is full"},
			{"there is not enough space", "the disk is full"},
			{"disk quota exceeded", "the disk quota is exhausted"},
			{"read-only file system", "the filesystem is read-only"},
			{"media is write protected", "the filesystem is read-only"},
			{"operation not supported", "the filesystem does not support a required operation"},
			{"function not implemented", "the filesystem does not support a required operation"},
			{"not supported", "the filesystem does not support a required operation"},
			{"input/output error", "the storage device reported an I/O error"},
			{"file too large", "the filesystem cannot hold a file this large"},
			{"too many open files", "the process ran out of file handles"},
	};

	/** What no wait fixes about this failure, or null when it may be another process holding a path. */
	static String condition(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof UnsupportedOperationException) return "the filesystem does not support a required operation";
			if (!(current instanceof FileSystemException fileSystem)) continue;
			String reason = fileSystem.getReason();
			if (reason != null) {
				String lower = reason.toLowerCase(Locale.ROOT);
				for (String[] known : REASONS) if (lower.contains(known[0])) return known[1];
			}
			String store = storeCondition(fileSystem.getFile());
			if (store != null) return store;
		}
		return null;
	}

	/** The message a failed apply records for a condition. */
	static String describe(String condition, IOException failure) {
		return "The update cannot continue because " + condition + ": " + failure.getMessage();
	}

	private static String storeCondition(String file) {
		if (file == null || file.isBlank()) return null;
		try {
			Path path = Path.of(file).toAbsolutePath();
			while (path != null && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) path = path.getParent();
			if (path == null) return null;
			FileStore store = Files.getFileStore(path);
			if (store.isReadOnly()) return "the filesystem is read-only";
			if (store.getUsableSpace() == 0) return "the disk is full";
			return null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
