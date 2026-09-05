package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Applies and verifies the filesystem's native read-only policy for immutable files. */
public final class ImmutableFiles {
	private static final Set<PosixFilePermission> WRITE_PERMISSIONS = EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
	private static final Set<AclEntryPermission> ACL_WRITE_PERMISSIONS = EnumSet.of(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA);

	private ImmutableFiles() {}

	public static void protect(Path file) throws IOException {
		Path normalized = file.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) throw new IOException("Immutable path is not a regular file: " + normalized);
		PosixFileAttributeView posix = Files.getFileAttributeView(normalized, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posix != null) {
			if (disjoint(posix.readAttributes().permissions(), WRITE_PERMISSIONS)) return;
			try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
				permissions.addAll(posix.readAttributes().permissions());
				permissions.removeAll(WRITE_PERMISSIONS);
				posix.setPermissions(permissions);
				if (!disjoint(posix.readAttributes().permissions(), WRITE_PERMISSIONS)) throw new IOException("Filesystem did not make immutable file read-only: " + normalized);
				channel.force(true);
			}
			return;
		}
		AclFileAttributeView acl = Files.getFileAttributeView(normalized, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		DosFileAttributeView dos = Files.getFileAttributeView(normalized, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (acl != null) {
			if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
			if (isAclProtected(normalized, acl)) return;
			try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				AclEntry protection = protectionEntry(normalized);
				List<AclEntry> entries = new ArrayList<>(acl.getAcl());
				if (!entries.contains(protection)) {
					entries.add(0, protection);
					acl.setAcl(entries);
				}
				if (!isAclProtected(normalized, acl)) throw new IOException("Filesystem did not protect immutable file ACL: " + normalized);
				channel.force(true);
			}
			return;
		}
		if (dos != null) {
			if (dos.readAttributes().isReadOnly()) return;
			try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				dos.setReadOnly(true);
				if (!dos.readAttributes().isReadOnly()) throw new IOException("Filesystem did not make immutable file read-only: " + normalized);
				channel.force(true);
			}
			return;
		}
		try {
			if (!normalized.toFile().setReadOnly()) throw new IOException("Filesystem has no enforceable read-only policy for immutable file: " + normalized);
			if (!blocksWrites(normalized)) throw new IOException("Filesystem did not enforce its read-only policy for immutable file: " + normalized);
		} catch (UnsupportedOperationException | IllegalArgumentException e) {
			throw new IOException("Filesystem has no enforceable read-only policy for immutable file: " + normalized, e);
		}
	}

	/** Clears the read-only policy so a sovereign owner can edit or delete the file. */
	public static void unprotect(Path file) throws IOException {
		Path normalized = file.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) return;
		PosixFileAttributeView posix = Files.getFileAttributeView(normalized, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posix != null) {
			if (!disjoint(posix.readAttributes().permissions(), WRITE_PERMISSIONS)) return;
			Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
			permissions.addAll(posix.readAttributes().permissions());
			permissions.add(PosixFilePermission.OWNER_WRITE);
			posix.setPermissions(permissions);
			return;
		}
		AclFileAttributeView acl = Files.getFileAttributeView(normalized, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (acl != null) {
			DosFileAttributeView dos = Files.getFileAttributeView(normalized, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
			if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
			List<AclEntry> entries = new ArrayList<>(acl.getAcl());
			if (entries.removeIf(entry -> entry.type() == AclEntryType.DENY)) acl.setAcl(entries);
			return;
		}
		DosFileAttributeView dos = Files.getFileAttributeView(normalized, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
	}

	public static boolean isProtected(Path file) throws IOException {
		Path normalized = file.toAbsolutePath().normalize();
		PosixFileAttributeView posix = Files.getFileAttributeView(normalized, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posix != null) return disjoint(posix.readAttributes().permissions(), WRITE_PERMISSIONS);
		AclFileAttributeView acl = Files.getFileAttributeView(normalized, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (acl != null) return isAclProtected(normalized, acl);
		DosFileAttributeView dos = Files.getFileAttributeView(normalized, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (dos != null) return dos.readAttributes().isReadOnly();
		return blocksWrites(normalized);
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
		AclFileAttributeView acl = Files.getFileAttributeView(normalized, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (acl != null) {
			AclEntry protection = protectionEntry(normalized);
			List<AclEntry> entries = new ArrayList<>(acl.getAcl());
			if (entries.removeIf(protection::equals)) acl.setAcl(entries);
			DosFileAttributeView dos = Files.getFileAttributeView(normalized, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
			if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
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

	private static AclEntry protectionEntry(Path file) throws IOException {
		return AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(file, LinkOption.NOFOLLOW_LINKS)).setPermissions(ACL_WRITE_PERMISSIONS).build();
	}

	private static boolean isAclProtected(Path file, AclFileAttributeView acl) throws IOException {
		return acl.getAcl().contains(protectionEntry(file));
	}

	private static boolean blocksWrites(Path file) throws IOException {
		try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			return false;
		} catch (AccessDeniedException e) {
			return true;
		}
	}
}
