package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/** Durable file installation operations that verify size and SHA-1 before publication. */
public final class VerifiedFileTransfer {
	private VerifiedFileTransfer() {}

	public static boolean copyAtomic(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		return copyAtomic(sourceFile, targetFile, expectedSize, expectedSha1, false, null);
	}

	public static boolean copyAtomic(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1, FileMetadataCache cache) throws IOException {
		return copyAtomic(sourceFile, targetFile, expectedSize, expectedSha1, false, cache);
	}

	private static boolean copyAtomic(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1, boolean immutable, FileMetadataCache cache) throws IOException {
		if (cache != null && immutable && FileIntegrity.matchesNamed(targetFile, expectedSize, expectedSha1, cache) || FileIntegrity.matches(targetFile, expectedSize, expectedSha1, cache)) {
			if (immutable) ImmutableFiles.protect(targetFile);
			record(cache, targetFile, expectedSha1);
			return false;
		}
		requireValidSource(sourceFile, expectedSize, expectedSha1, cache);

		Path temporary = copyToVerifiedTemporary(sourceFile, targetFile, expectedSize, expectedSha1);
		try {
			if (immutable) ImmutableFiles.protect(temporary);
			moveAtomicReplace(temporary, targetFile);
			FileTrees.forceDirectory(temporary.getParent());
			if (immutable) ImmutableFiles.protect(targetFile);
			record(cache, targetFile, expectedSha1);
			return true;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/** Replaces an immutable object and enforces its read-only policy before returning. */
	public static boolean copyAtomicImmutable(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		return copyAtomic(sourceFile, targetFile, expectedSize, expectedSha1, true, null);
	}

	public static boolean copyAtomicImmutable(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1, FileMetadataCache cache) throws IOException {
		return copyAtomic(sourceFile, targetFile, expectedSize, expectedSha1, true, cache);
	}

	/** Copies a verified file without replacing a destination created by another operation. */
	public static boolean copyCreateOnly(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		return copyCreateOnly(sourceFile, targetFile, expectedSize, expectedSha1, null);
	}

	public static boolean copyCreateOnly(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1, FileMetadataCache cache) throws IOException {
		requireValidSource(sourceFile, expectedSize, expectedSha1, cache);
		boolean published = ImmutableFilePublisher.publishCreateOnlyCopy(sourceFile, targetFile, path -> {
			if (Files.size(path) != expectedSize) throw new IOException("Immutable copy has different size: " + path);
		});
		record(cache, targetFile, expectedSha1);
		return published;
	}

	/** Installs an immutable object as a hard link, with verified-copy fallback when linking is unavailable. */
	public static boolean linkAtomic(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		return linkAtomic(sourceFile, targetFile, expectedSize, expectedSha1, null);
	}

	public static boolean linkAtomic(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1, FileMetadataCache cache) throws IOException {
		if (FileIntegrity.matches(targetFile, expectedSize, expectedSha1, cache)) {
			ImmutableFiles.protect(targetFile);
			record(cache, targetFile, expectedSha1);
			return false;
		}
		if (cache != null) {
			if (!FileIntegrity.matchesNamed(sourceFile, expectedSize, expectedSha1, cache)) throw new IOException("Source file failed size/SHA-1 verification: " + sourceFile);
		} else {
			requireValidSource(sourceFile, expectedSize, expectedSha1, null);
		}
		ImmutableFiles.protect(sourceFile);
		Path parent = requireTargetParent(targetFile);
		Path temporary = Files.createTempFile(parent, "." + targetFile.getFileName() + ".", ".tmp");
		Files.deleteIfExists(temporary);
		try {
			try {
				Files.createLink(temporary, sourceFile);
			} catch (UnsupportedOperationException | FileSystemException unsupportedLink) {
				if (!expectedSha1.equalsIgnoreCase(HashUtils.copyAndSha1(sourceFile, temporary))) throw new IOException("Linked file failed SHA-1 verification: " + temporary);
			}
			if (Files.size(temporary) != expectedSize) throw new IOException("Linked file failed size verification: " + temporary);
			ImmutableFiles.protect(temporary);
			moveAtomicReplace(temporary, targetFile);
			FileTrees.forceDirectory(parent);
			ImmutableFiles.protect(targetFile);
			// Seed the projection-path record so later lookups do not hash; the named source record stays valid
			// because the immutable tripwire compares size, mtime, and inode, none of which link() or chmod() move.
			record(cache, targetFile, expectedSha1);
			return true;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public static void promoteAtomic(Path temporary, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		promoteAtomic(temporary, targetFile, expectedSize, expectedSha1, null);
	}

	public static void promoteAtomic(Path temporary, Path targetFile, long expectedSize, String expectedSha1, FileMetadataCache cache) throws IOException {
		FileTrees.forceFile(temporary);
		if (!FileIntegrity.matches(temporary, expectedSize, expectedSha1))
			throw new IOException("Downloaded file failed size/SHA-1 verification: " + temporary);
		ImmutableFiles.protect(temporary);
		Path targetParent = requireTargetParent(targetFile);
		boolean crossFileSystem = false;
		try {
			moveAtomicReplace(temporary, targetFile);
		} catch (AtomicMoveNotSupportedException crossFileSystemFailure) {
			promoteAcrossFileSystems(temporary, targetFile, targetParent, expectedSize, expectedSha1, crossFileSystemFailure);
			crossFileSystem = true;
		}
		FileTrees.forceDirectory(targetParent);
		if (crossFileSystem) ImmutableFiles.deleteIfExists(temporary);
		ImmutableFiles.protect(targetFile);
		record(cache, targetFile, expectedSha1);
	}

	private static void promoteAcrossFileSystems(Path temporary, Path targetFile, Path targetParent, long expectedSize, String expectedSha1,
			AtomicMoveNotSupportedException crossFileSystem) throws IOException {
		Path targetTemporary = Files.createTempFile(targetParent, "." + targetFile.getFileName() + ".", ".tmp");
		try {
			Files.copy(temporary, targetTemporary, StandardCopyOption.REPLACE_EXISTING);
			ImmutableFiles.allowOwnerWrite(targetTemporary);
			FileTrees.forceFile(targetTemporary);
			if (!FileIntegrity.matches(targetTemporary, expectedSize, expectedSha1))
				throw new IOException("Cross-filesystem promotion failed size/SHA-1 verification: " + targetTemporary, crossFileSystem);
			ImmutableFiles.protect(targetTemporary);
			moveAtomicReplace(targetTemporary, targetFile);
		} finally {
			Files.deleteIfExists(targetTemporary);
		}
	}

	private static void requireValidSource(Path sourceFile, long expectedSize, String expectedSha1, FileMetadataCache cache) throws IOException {
		if (!FileIntegrity.matches(sourceFile, expectedSize, expectedSha1, cache))
			throw new IOException("Source file failed size/SHA-1 verification: " + sourceFile);
	}

	private static void record(FileMetadataCache cache, Path file, String sha1) throws IOException {
		if (cache == null) return;
		cache.overwriteCache(file, sha1);
	}

	private static Path copyToVerifiedTemporary(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		Path parent = requireTargetParent(targetFile);
		Path temporary = Files.createTempFile(parent, "." + targetFile.getFileName() + ".", ".tmp");
		boolean valid = false;
		try {
			String copied = HashUtils.copyAndSha1(sourceFile, temporary);
			ImmutableFiles.allowOwnerWrite(temporary);
			FileTrees.forceFile(temporary);
			valid = Files.size(temporary) == expectedSize && expectedSha1.equalsIgnoreCase(copied);
			if (!valid) throw new IOException("Copied file failed size/SHA-1 verification: " + temporary);
			return temporary;
		} finally {
			if (!valid) Files.deleteIfExists(temporary);
		}
	}

	private static Path requireTargetParent(Path targetFile) throws IOException {
		Path parent = targetFile.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException("Target path has no parent: " + targetFile);
		Files.createDirectories(parent);
		return parent;
	}

	private static void moveAtomicReplace(Path sourceFile, Path targetFile) throws IOException {
		DurableFiles.replace(sourceFile, targetFile);
	}

}
