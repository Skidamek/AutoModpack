package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/** Recursive and directory-level filesystem operations. */
public final class FileTrees {
	private FileTrees() {}

	public static void moveAtomic(Path sourceDirectory, Path targetDirectory) throws IOException {
		if (sourceDirectory.toAbsolutePath().normalize().getParent() == null || targetDirectory.toAbsolutePath().normalize().getParent() == null)
			throw new IOException("Directory move requires concrete parent directories");
		try {
			Files.move(sourceDirectory, targetDirectory, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			throw new IOException("Atomic directory replacement is unsupported for " + targetDirectory, e);
		}
	}

	public static void delete(Path directory) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.isSymbolicLink(directory)) {
			Files.delete(directory);
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}
}
