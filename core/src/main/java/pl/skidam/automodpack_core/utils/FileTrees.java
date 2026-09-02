package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.stream.Stream;

/** Recursive and directory-level filesystem operations. */
public final class FileTrees {
	private FileTrees() {}

	/** Moves a directory whose caller has a durable recovery journal and validates the result. */
	public static void moveRecoverableDirectory(Path sourceDirectory, Path targetDirectory) throws IOException {
		Path sourceParent = sourceDirectory.toAbsolutePath().normalize().getParent();
		Path targetParent = targetDirectory.toAbsolutePath().normalize().getParent();
		if (sourceParent == null || targetParent == null)
			throw new IOException("Directory move requires concrete parent directories");
		try {
			Files.move(sourceDirectory, targetDirectory, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException | UnsupportedOperationException atomicMoveFailure) {
			try {
				Files.move(sourceDirectory, targetDirectory);
			} catch (IOException e) {
				e.addSuppressed(atomicMoveFailure);
				throw e;
			}
		}
		forceDirectory(sourceParent);
		if (!sourceParent.equals(targetParent)) forceDirectory(targetParent);
	}

	/** Forces a directory entry update when the filesystem exposes directory channels. */
	public static void forceDirectory(Path directory) {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			channel.force(true);
		} catch (IOException | UnsupportedOperationException | IllegalArgumentException ignored) {
			// Java does not expose a portable directory-sync capability probe.
		}
	}

	/** Forces file content and metadata to stable storage. */
	public static void forceFile(Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			channel.force(true);
		}
	}

	/** Creates a managed directory and rejects symbolic-link aliases. */
	public static void createManagedDirectory(Path directory, String description) throws IOException {
		if (Files.isSymbolicLink(directory)) throw new IOException("Managed " + description + " cannot be a symbolic link: " + directory);
		Files.createDirectories(directory);
		if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Managed " + description + " is not a directory: " + directory);
	}

	/** Requires a non-symbolic-link regular file. */
	public static Path requireRegularFile(Path file, String description) throws IOException {
		requireNoSymbolicLink(file, description);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException(description + " is not a regular file: " + file);
		return file;
	}

	/** Requires a non-symbolic-link directory. */
	public static void requireDirectory(Path directory, String description) throws IOException {
		requireNoSymbolicLink(directory, description);
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException(description + " is not a directory: " + directory);
	}

	/** Requires a path that is not a symbolic link. */
	public static void requireNoSymbolicLink(Path path, String description) throws IOException {
		if (Files.isSymbolicLink(path)) throw new IOException(description + " contains a symbolic link: " + path);
	}

	/**
	 * Requires every component from {@code root} down to and including {@code target} to be real
	 * directories or a real file, never a symbolic link. This is the canonical confinement check
	 * for every managed root; do not re-implement it locally.
	 */
	public static void requireNoSymbolicLinkDescendants(Path root, Path target, String description) throws IOException {
		Path base = root.toAbsolutePath().normalize();
		Path resolved = target.toAbsolutePath().normalize();
		if (!resolved.startsWith(base)) throw new IOException(description + " escapes its root: " + target);
		if (Files.isSymbolicLink(base)) throw new IOException(description + " root is a symbolic link: " + base);
		Path current = base;
		for (Path component : base.relativize(resolved)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) throw new IOException(description + " contains a symbolic link: " + current);
		}
	}

	/** Reports whether every component from {@code root} down to {@code target} avoids symbolic links. */
	public static boolean hasNoSymbolicLinkDescendants(Path root, Path target) {
		try {
			requireNoSymbolicLinkDescendants(root, target, "Path");
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	public static void delete(Path directory) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.isSymbolicLink(directory)) {
			ImmutableFiles.deleteIfExists(directory);
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) ImmutableFiles.deleteIfExists(path);
		}
	}

	/** Removes empty parent directories without removing the retained root. */
	public static void pruneEmptyAncestors(Path removedPath, Path retainedRoot) throws IOException {
		Path root = retainedRoot.toAbsolutePath().normalize();
		Path removed = removedPath.toAbsolutePath().normalize();
		if (!removed.startsWith(root) || removed.equals(root)) throw new IOException("Removed path must be beneath the retained root");
		for (Path directory = removed.getParent(); directory != null && !directory.equals(root); directory = directory.getParent()) {
			if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
			if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Empty-directory cleanup found a non-directory ancestor: " + directory);
			try {
				Files.delete(directory);
			} catch (DirectoryNotEmptyException e) {
				return;
			}
		}
	}
}
