package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Durably publishes immutable files without replacing an existing target. */
public final class ImmutableFilePublisher {
	private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
	private ImmutableFilePublisher() {}

	public static boolean publishBytes(Path target, byte[] bytes, ExistingFileValidator existingFileValidator) throws IOException {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(bytes, "bytes");
		Path parent = requireParent(target);
		if (validateExisting(target, existingFileValidator, null)) {
			ImmutableFiles.protect(target);
			if (!ImmutableFiles.isProtected(target)) throw new IOException("Published immutable file is writable: " + target);
			return false;
		}
		Path temporary = Files.createTempFile(parent, ".immutable-", ".tmp");
		try {
			write(temporary, bytes);
			ImmutableFiles.protect(temporary);
			boolean published = publishTemporary(temporary, target, existingFileValidator);
			if (!ImmutableFiles.isProtected(target)) throw new IOException("Published immutable file is writable: " + target);
			return published;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/** Publishes a verified immutable source, using a copy only when a hard link is unavailable. */
	public static boolean publishFile(Path source, Path target, ExistingFileValidator existingFileValidator) throws IOException {
		Objects.requireNonNull(source, "source");
		Path parent = requireParent(target);
		if (validateExisting(target, existingFileValidator, null)) {
			ImmutableFiles.protect(target);
			return false;
		}
		ImmutableFiles.protect(source);
		try {
			Files.createLink(target, source);
			FileTrees.forceDirectory(parent);
			return true;
		} catch (FileAlreadyExistsException e) {
			validate(existingFileValidator, target, e);
			return false;
		} catch (UnsupportedOperationException | FileSystemException linkFailure) {
			return publishCopy(source, target, parent, existingFileValidator, linkFailure, true);
		}
	}

	/** Publishes a create-only writable copy without ever linking the target to the source inode. */
	public static boolean publishCreateOnlyCopy(Path source, Path target, ExistingFileValidator existingFileValidator) throws IOException {
		Objects.requireNonNull(source, "source");
		Path parent = requireParent(target);
		if (validateExisting(target, existingFileValidator, null)) return false;
		return publishCopy(source, target, parent, existingFileValidator, null, false);
	}

	private static boolean publishCopy(Path source, Path target, Path parent, ExistingFileValidator existingFileValidator, Exception linkFailure, boolean protect) throws IOException {
		Path temporary = Files.createTempFile(parent, ".immutable-", ".tmp");
		try {
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			ImmutableFiles.allowOwnerWrite(temporary);
			FileTrees.forceFile(temporary);
			validate(existingFileValidator, temporary, null);
			if (protect) ImmutableFiles.protect(temporary);
			try {
				return publishTemporary(temporary, target, existingFileValidator);
			} catch (IOException e) {
				if (linkFailure != null) e.addSuppressed(linkFailure);
				throw e;
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static boolean publishTemporary(Path temporary, Path target, ExistingFileValidator existingFileValidator) throws IOException {
		Objects.requireNonNull(temporary, "temporary");
		Path parent = requireParent(target);
		if (validateExisting(target, existingFileValidator, null)) return false;
		boolean published = false;
		try {
			try {
				Files.createLink(target, temporary);
			} catch (FileAlreadyExistsException e) {
				validate(existingFileValidator, target, e);
				return false;
			} catch (UnsupportedOperationException | FileSystemException linkFailure) {
				return publishAtomicMoveLocked(temporary, target, parent, existingFileValidator, linkFailure);
			}
			published = true;
			return true;
		} finally {
			if (published) FileTrees.forceDirectory(parent);
		}
	}

	private static boolean publishAtomicMoveLocked(Path temporary, Path target, Path parent, ExistingFileValidator existingFileValidator, Exception linkFailure) throws IOException {
		Path lockPath = publicationLockPath(parent);
		ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
		jvmLock.lock();
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); FileLock ignored = channel.lock()) {
			if (validateExisting(target, existingFileValidator, null)) return false;
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
				FileTrees.forceDirectory(parent);
				return true;
			} catch (FileAlreadyExistsException e) {
				validate(existingFileValidator, target, e);
				return false;
			} catch (IOException e) {
				e.addSuppressed(linkFailure);
				throw e;
			}
		} finally {
			jvmLock.unlock();
		}
	}

	private static Path publicationLockPath(Path parent) throws IOException {
		Path name = parent.getFileName();
		Path ancestor = parent.getParent();
		if (name == null || ancestor == null) throw new IOException("Immutable target directory cannot host a publication lock: " + parent);
		return ancestor.resolve("." + name + ".publication.lock").normalize();
	}

	private static void write(Path temporary, byte[] bytes) throws IOException {
		try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) channel.write(buffer);
			channel.force(true);
		}
	}

	private static Path requireParent(Path target) throws IOException {
		Objects.requireNonNull(target, "target");
		Path parent = target.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException("Immutable target has no parent: " + target);
		Files.createDirectories(parent);
		return parent;
	}

	private static boolean validateExisting(Path target, ExistingFileValidator existingFileValidator, Exception publicationRace) throws IOException {
		Objects.requireNonNull(existingFileValidator, "existing file validator");
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
		validate(existingFileValidator, target, publicationRace);
		return true;
	}

	private static void validate(ExistingFileValidator existingFileValidator, Path path, Exception publicationRace) throws IOException {
		try {
			existingFileValidator.validate(path);
		} catch (IOException e) {
			if (publicationRace != null) e.addSuppressed(publicationRace);
			throw e;
		}
	}

	@FunctionalInterface
	public interface ExistingFileValidator {
		void validate(Path path) throws IOException;
	}
}
