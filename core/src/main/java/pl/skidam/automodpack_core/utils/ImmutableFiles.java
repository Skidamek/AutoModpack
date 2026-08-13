package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/** Applies and verifies the filesystem's native read-only policy for immutable files. */
public final class ImmutableFiles {
	private static final Set<PosixFilePermission> WRITE_PERMISSIONS = EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);

	private ImmutableFiles() {}

	public static void protect(Path file) throws IOException {
		Path normalized = file.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) throw new IOException("Immutable path is not a regular file: " + normalized);
		if (isProtected(normalized)) return;
		try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			PosixFileAttributeView posix = Files.getFileAttributeView(normalized, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
			if (posix != null) {
				Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
				permissions.addAll(posix.readAttributes().permissions());
				permissions.removeAll(WRITE_PERMISSIONS);
				posix.setPermissions(permissions);
				if (!disjoint(posix.readAttributes().permissions(), WRITE_PERMISSIONS)) throw new IOException("Filesystem did not make immutable file read-only: " + normalized);
			} else {
				DosFileAttributeView dos = Files.getFileAttributeView(normalized, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
				if (dos != null) {
					dos.setReadOnly(true);
					if (!dos.readAttributes().isReadOnly()) throw new IOException("Filesystem did not make immutable file read-only: " + normalized);
				} else if (!normalized.toFile().setReadOnly()) throw new IOException("Filesystem has no enforceable read-only policy for immutable file: " + normalized);
			}
			channel.force(true);
		}
	}

	public static boolean isProtected(Path file) throws IOException {
		Path normalized = file.toAbsolutePath().normalize();
		PosixFileAttributeView posix = Files.getFileAttributeView(normalized, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posix != null) return disjoint(posix.readAttributes().permissions(), WRITE_PERMISSIONS);
		DosFileAttributeView dos = Files.getFileAttributeView(normalized, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		return dos != null && dos.readAttributes().isReadOnly();
	}

	/** Deletes an immutable name, clearing the DOS read-only bit only when Windows requires it. */
	public static boolean deleteIfExists(Path file) throws IOException {
		try {
			return Files.deleteIfExists(file);
		} catch (AccessDeniedException denied) {
			DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
			if (dos == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
			if (!dos.readAttributes().isReadOnly()) throw denied;
			dos.setReadOnly(false);
			return Files.deleteIfExists(file);
		}
	}

	/** Makes a newly copied private temporary writable before it is forced and published. */
	static void allowOwnerWrite(Path file) throws IOException {
		Path normalized = file.toAbsolutePath().normalize();
		PosixFileAttributeView posix = Files.getFileAttributeView(normalized, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posix != null) {
			Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
			permissions.addAll(posix.readAttributes().permissions());
			permissions.add(PosixFilePermission.OWNER_WRITE);
			posix.setPermissions(permissions);
			return;
		}
		DosFileAttributeView dos = Files.getFileAttributeView(normalized, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (dos != null) {
			dos.setReadOnly(false);
			return;
		}
		if (!normalized.toFile().setWritable(true, true)) throw new IOException("Cannot make publication temporary writable: " + normalized);
	}

	private static boolean disjoint(Set<PosixFilePermission> left, Set<PosixFilePermission> right) {
		for (PosixFilePermission permission : right) if (left.contains(permission)) return false;
		return true;
	}
}
