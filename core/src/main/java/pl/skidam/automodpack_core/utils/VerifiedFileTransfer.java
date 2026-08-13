package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Durable file installation operations that verify size and SHA-1 before publication. */
public final class VerifiedFileTransfer {
	private VerifiedFileTransfer() {}

	public static boolean copyAtomic(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		return copyAtomic(sourceFile, targetFile, expectedSize, expectedSha1, false);
	}

	private static boolean copyAtomic(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1, boolean immutable) throws IOException {
		if (FileIntegrity.matches(targetFile, expectedSize, expectedSha1)) {
			if (immutable) ImmutableFiles.protect(targetFile);
			return false;
		}
		requireValidSource(sourceFile, expectedSize, expectedSha1);

		Path temporary = copyToVerifiedTemporary(sourceFile, targetFile, expectedSize, expectedSha1);
		try {
			if (immutable) ImmutableFiles.protect(temporary);
			moveAtomicReplace(temporary, targetFile);
			FileTrees.forceDirectory(temporary.getParent());
			return true;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/** Replaces an immutable object and enforces its read-only policy before returning. */
	public static boolean copyAtomicImmutable(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		return copyAtomic(sourceFile, targetFile, expectedSize, expectedSha1, true);
	}

	/** Copies a verified file without replacing a destination created by another operation. */
	public static boolean copyCreateOnly(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		requireValidSource(sourceFile, expectedSize, expectedSha1);
		return ImmutableFilePublisher.publishCreateOnlyCopy(sourceFile, targetFile, path -> {
			if (!FileIntegrity.matches(path, expectedSize, expectedSha1)) throw new IOException("Immutable copy has different bytes: " + path);
		});
	}

	/** Installs an immutable object as a hard link, with verified-copy fallback when linking is unavailable. */
	public static boolean linkAtomic(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		if (FileIntegrity.matches(targetFile, expectedSize, expectedSha1)) {
			ImmutableFiles.protect(targetFile);
			return false;
		}
		ImmutableFiles.protect(sourceFile);
		requireValidSource(sourceFile, expectedSize, expectedSha1);
		Path parent = requireTargetParent(targetFile);
		Path temporary = Files.createTempFile(parent, "." + targetFile.getFileName() + ".", ".tmp");
		Files.deleteIfExists(temporary);
		try {
			try {
				Files.createLink(temporary, sourceFile);
			} catch (UnsupportedOperationException | FileSystemException unsupportedLink) {
				Files.createFile(temporary);
				Files.copy(sourceFile, temporary, StandardCopyOption.REPLACE_EXISTING);
				ImmutableFiles.allowOwnerWrite(temporary);
				FileTrees.forceFile(temporary);
			}
			if (!FileIntegrity.matches(temporary, expectedSize, expectedSha1))
				throw new IOException("Linked file failed size/SHA-1 verification: " + temporary);
			ImmutableFiles.protect(temporary);
			moveAtomicReplace(temporary, targetFile);
			FileTrees.forceDirectory(parent);
			return true;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	public static void promoteAtomic(Path temporary, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		FileTrees.forceFile(temporary);
		if (!FileIntegrity.matches(temporary, expectedSize, expectedSha1))
			throw new IOException("Downloaded file failed size/SHA-1 verification: " + temporary);
		ImmutableFiles.protect(temporary);
		Path targetParent = requireTargetParent(targetFile);
		try {
			moveAtomicReplace(temporary, targetFile);
		} catch (AtomicMoveNotSupportedException crossFileSystem) {
			promoteAcrossFileSystems(temporary, targetFile, targetParent, expectedSize, expectedSha1, crossFileSystem);
		}
		FileTrees.forceDirectory(targetParent);
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

	private static void requireValidSource(Path sourceFile, long expectedSize, String expectedSha1) throws IOException {
		if (!FileIntegrity.matches(sourceFile, expectedSize, expectedSha1))
			throw new IOException("Source file failed size/SHA-1 verification: " + sourceFile);
	}

	private static Path copyToVerifiedTemporary(Path sourceFile, Path targetFile, long expectedSize, String expectedSha1) throws IOException {
		Path parent = requireTargetParent(targetFile);
		Path temporary = Files.createTempFile(parent, "." + targetFile.getFileName() + ".", ".tmp");
		boolean valid = false;
		try {
			Files.copy(sourceFile, temporary, StandardCopyOption.REPLACE_EXISTING);
			ImmutableFiles.allowOwnerWrite(temporary);
			FileTrees.forceFile(temporary);
			valid = FileIntegrity.matches(temporary, expectedSize, expectedSha1);
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
