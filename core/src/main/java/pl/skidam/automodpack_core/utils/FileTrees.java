package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.utils.cache.FileCache;

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

	/**
	 * Whether a file in {@code sourceDirectory} can be hard-linked into {@code targetDirectory}: one filesystem, both permitting links. Links a temp file and removes it; the per-operation link attempt stays the
	 * authority.
	 */
	public static boolean hardLinkSupported(Path sourceDirectory, Path targetDirectory) throws IOException {
		Path probe;
		try {
			probe = Files.createTempFile(sourceDirectory, ".hardlink-probe-", DurableFiles.TEMPORARY_SUFFIX);
		} catch (IOException | RuntimeException e) {
			return false;
		}
		Path link = targetDirectory.resolve(probe.getFileName() + ".link");
		try {
			Files.createLink(link, probe);
			return true;
		} catch (UnsupportedOperationException | IOException e) {
			return false;
		} finally {
			try {
				ImmutableFiles.deleteIfExists(link);
				ImmutableFiles.deleteIfExists(probe);
			} catch (IOException cleanupFailure) {
				// The probe is disposable and carries the swept temporary suffix; a failed cleanup must not turn a
				// should-fallback answer into a boot failure.
			}
		}
	}

	/** Creates a managed directory and rejects symbolic-link aliases. */
	public static void createManagedDirectory(Path directory, String description) throws IOException {
		if (isRedirectingLink(directory)) throw new IOException("Managed " + description + " cannot be a symbolic link: " + directory);
		OsPaths.requirePublishableDirectory(directory);
		Files.createDirectories(directory);
		if (isRedirectingLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
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
		if (isRedirectingLink(path)) throw new IOException(description + " contains a symbolic link: " + path);
	}

	/**
	 * Requires a directory whose location the player configured: the game directory or the loader-visible mods
	 * directory. The root itself may resolve through a junction or symbolic link because relocating those directories
	 * that way is a supported setup; every entry inside them is still checked on its own.
	 */
	public static void requirePlayerDirectory(Path directory, String description) throws IOException {
		if (!Files.isDirectory(directory)) throw new IOException(description + " is not a directory: " + directory);
	}

	/**
	 * Whether {@code path} itself redirects like a link: a symbolic link on every platform, and on Windows a directory
	 * junction (or another non-benign reparse point) that {@code Files.isSymbolicLink} reports as an ordinary directory
	 * while opening the path still follows the target. Every managed-root scan uses this instead of
	 * {@link Files#isSymbolicLink(Path)}; keeping the message unchanged preserves the refusal users and tests see.
	 */
	public static boolean isRedirectingLink(Path path) {
		if (PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS) {
			try {
				return FileCache.statSnapshot(path).isRedirectingLink();
			} catch (IOException e) {
				if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true;
			}
		}
		return Files.isSymbolicLink(path);
	}

	/**
	 * Resolves {@code relativePath} beneath {@code root} and requires the whole walk from the root down to the resolved
	 * target to stay confined and free of redirects. Named player-owned directories may redirect; everything below them
	 * stays strict. This is the canonical rooted resolve for every managed root; do not re-implement it locally.
	 */
	public static Path resolveConfined(Path root, String relativePath, String description, RootTrust trust, Collection<Path> trustedRedirects) throws IOException {
		return Confinement.under(root, trust, trustedRedirects).resolve(relativePath, description);
	}

	/**
	 * Requires every component from {@code root} down to and including {@code target} to be real
	 * directories or a real file, never a symbolic link. This is the canonical confinement check
	 * for every managed root; do not re-implement it locally.
	 */
	public static void requireNoSymbolicLinkDescendants(Path root, Path target, String description) throws IOException {
		Confinement.under(root).require(target, description);
	}

	/**
	 * Requires every component from {@code root} down to and including {@code target} to be real directories or a real
	 * file. Named player-owned directories may redirect; everything below them stays strict. This is the canonical
	 * confinement check for every managed root; do not re-implement it locally.
	 */
	public static void requireNoSymbolicLinkDescendants(Path root, Path target, String description, RootTrust trust, Collection<Path> trustedRedirects) throws IOException {
		Confinement.under(root, trust, trustedRedirects).require(target, description);
	}

	/**
	 * Who chose a confinement root. An app-owned root is part of the managed layout and must not redirect; the game
	 * directory and the loader-visible mods directory are the player's own and may legitimately be relocated through a
	 * junction or symbolic link.
	 */
	public enum RootTrust {
		MANAGED,
		PLAYER_CONFIGURED
	}

	/**
	 * One pass's proof that the directory prefixes under a root contain no symbolic links. Files are
	 * still checked on every resolution, so a leaf swapped for a link after an earlier entry proved its
	 * parent is refused; only directory prefixes shared by many entries are proved once. The proof lives
	 * for one validation pass and must not outlive it: a later swap must be seen by the next pass.
	 */
	public static final class Confinement {
		private final Path root;
		private final boolean rootMayRedirect;
		private final Set<Path> trustedRedirects;
		private Set<Path> provenDirectories;
		private boolean rootProven;

		private Confinement(Path root, RootTrust trust, Collection<Path> trustedRedirects) {
			this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
			this.rootMayRedirect = Objects.requireNonNull(trust, "trust") == RootTrust.PLAYER_CONFIGURED;
			Set<Path> redirects = new HashSet<>();
			for (Path redirect : Objects.requireNonNull(trustedRedirects, "trustedRedirects")) {
				Path normalized = Objects.requireNonNull(redirect, "trusted redirect").toAbsolutePath().normalize();
				if (!normalized.startsWith(this.root)) throw new IllegalArgumentException("Trusted redirect escapes its root: " + normalized);
				redirects.add(normalized);
			}
			this.trustedRedirects = Set.copyOf(redirects);
		}

		public static Confinement under(Path root) {
			return new Confinement(root, RootTrust.MANAGED, Set.of());
		}

		public static Confinement under(Path root, RootTrust trust) {
			return new Confinement(root, trust, Set.of());
		}

		/** A confinement where the root and the named player-owned directories may themselves be a redirect. */
		public static Confinement under(Path root, RootTrust trust, Collection<Path> trustedRedirects) {
			return new Confinement(root, trust, trustedRedirects);
		}

		public Path root() {
			return root;
		}

		/** Resolves a root-relative path, proving unvisited directory prefixes and always checking the leaf. */
		public Path resolve(String relativePath, String description) throws IOException {
			Path resolved = root.resolve(relativePath).normalize();
			requireProven(resolved, resolved, description);
			return resolved;
		}

		/** Requires an already-absolute target to stay beneath the root without symbolic links on the way. */
		public void require(Path target, String description) throws IOException {
			Path resolved = target.toAbsolutePath().normalize();
			requireProven(resolved, target, description);
		}

		/**
		 * Forgets the proof of everything a removal may have deleted or replaced: the removed path's parent, every
		 * proven directory beneath it, and every proven ancestor above it (empty-directory pruning can remove those
		 * too). The next resolution under any of them walks the chain again, so a directory recreated as a symbolic
		 * link after the removal is refused. Over-forgetting a surviving sibling costs a few stats; keeping a stale
		 * proof would be a confinement hole.
		 */
		public void invalidate(Path removedPath) {
			Path resolved = removedPath.toAbsolutePath().normalize();
			Path directory = resolved.getParent();
			if (directory == null || !directory.startsWith(root)) {
				// Not a path this proof speaks for: drop everything rather than keep an answer we cannot place.
				provenDirectories = null;
				return;
			}
			if (provenDirectories != null) provenDirectories.removeIf(proven -> proven.startsWith(directory) || directory.startsWith(proven));
		}

		private boolean mayRedirect(Path path) {
			if (path.equals(root)) return rootMayRedirect;
			return trustedRedirects.contains(path);
		}

		private void requireProven(Path resolved, Path reported, String description) throws IOException {
			if (!resolved.startsWith(root)) throw new IOException(description + " escapes its root: " + reported);
			if (!rootProven) {
				if (!mayRedirect(root) && isRedirectingLink(root)) throw new IOException(description + " root is a symbolic link: " + root);
				rootProven = true;
			}
			if (resolved.equals(root)) return;
			proveDirectory(resolved.getParent(), description);
			if (isRedirectingLink(resolved) && !mayRedirect(resolved)) throw new IOException(description + " contains a symbolic link: " + resolved);
		}

		/** Proves the chain from the root down to {@code directory}, stopping at an already-proven prefix. */
		private void proveDirectory(Path directory, String description) throws IOException {
			if (directory == null || directory.equals(root) || provenDirectories != null && provenDirectories.contains(directory)) return;
			proveDirectory(directory.getParent(), description);
			if (isRedirectingLink(directory) && !mayRedirect(directory)) throw new IOException(description + " contains a symbolic link: " + directory);
			if (provenDirectories == null) provenDirectories = new HashSet<>();
			provenDirectories.add(directory);
		}
	}

	/** Reports whether every component from {@code root} to {@code target} stays real, where named player-owned files may redirect. */
	public static boolean hasNoSymbolicLinkDescendants(Path root, Path target, RootTrust trust, Collection<Path> trustedRedirects) {
		try {
			requireNoSymbolicLinkDescendants(root, target, "Path", trust, trustedRedirects);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	public static void delete(Path directory) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
		if (isRedirectingLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			ImmutableFiles.deleteIfExists(directory);
			return;
		}
		try {
			Files.delete(directory);
			return;
		} catch (DirectoryNotEmptyException notEmpty) {
			// Fall through to the recursive walk only when there is actually a tree to walk.
		}
		Files.walkFileTree(directory, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
				// A junction is reported as a directory but opening it follows the target; remove the reparse point and
				// never descend, or a managed cleanup would walk outside the tree.
				if (!dir.equals(directory) && isRedirectingLink(dir)) {
					ImmutableFiles.deleteIfExists(dir);
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				ImmutableFiles.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
				if (failure != null) throw failure;
				ImmutableFiles.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Removes empty parent directories without removing the retained root. If a directory cannot be removed, stop. */
	public static void pruneEmptyAncestors(Path removedPath, Path retainedRoot) throws IOException {
		Path root = retainedRoot.toAbsolutePath().normalize();
		Path removed = removedPath.toAbsolutePath().normalize();
		if (!removed.startsWith(root) || removed.equals(root)) throw new IOException("Removed path must be beneath the retained root");
		for (Path directory = removed.getParent(); directory != null && !directory.equals(root); directory = directory.getParent()) {
			if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
			if (isRedirectingLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Empty-directory cleanup found a non-directory ancestor: " + directory);
			try {
				Files.delete(directory);
			} catch (FileSystemException e) {
				return;
			}
		}
	}
}
