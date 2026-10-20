package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileStore;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies and verifies the filesystem's native read-only policy for immutable files. A filesystem that cannot enforce the
 * policy at all (Android FUSE ignores it silently) degrades to writable files with one loud log per filesystem instead of
 * failing every publication; hash verification stays the integrity authority either way.
 */
public final class ImmutableFiles {
	private static final Set<PosixFilePermission> WRITE_PERMISSIONS = EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
	private static final Set<AclEntryPermission> ACL_WRITE_PERMISSIONS = EnumSet.of(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA);
	private static final ConcurrentHashMap<FileStore, Boolean> READ_ONLY_ENFORCEMENT = new ConcurrentHashMap<>();

	private ImmutableFiles() {}

	public static void protect(Path file) throws IOException {
		Path normalized = file.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) throw new IOException("Immutable path is not a regular file: " + normalized);
		if (!enforcementWorks(normalized)) return; // the probe already logged this filesystem's verdict once
		enforce(normalized);
	}

	/**
	 * Probes the filesystem once per JVM on a throwaway file with the same enforcement path, so a filesystem that only
	 * pretends to be read-only is told apart from a file that failed protection for its own reasons - those still throw.
	 */
	private static boolean enforcementWorks(Path file) throws IOException {
		FileStore store;
		Path probe;
		try {
			store = Files.getFileStore(file);
			Boolean known = READ_ONLY_ENFORCEMENT.get(store);
			if (known != null) return known;
			probe = Files.createTempFile(file.getParent(), ".read-only-probe-", DurableFiles.TEMPORARY_SUFFIX);
		} catch (IOException | RuntimeException e) {
			// Nothing to probe with, so the per-file enforcement below stays the judge.
			return true;
		}
		try {
			enforce(probe);
			READ_ONLY_ENFORCEMENT.put(store, true);
			return true;
		} catch (UnenforceableReadOnlyException e) {
			LOGGER.warn("The filesystem holding {} does not enforce read-only files; immutable files on it stay writable", file, e);
			READ_ONLY_ENFORCEMENT.put(store, false);
			return false;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Read-only probe of {} failed; per-file enforcement stays the judge", file, e);
			return true;
		} finally {
			removeProbe(probe);
		}
	}

	private static void removeProbe(Path probe) {
		if (probe == null) return;
		try {
			deleteIfExists(probe);
		} catch (IOException cleanupFailure) {
			// The probe is disposable and carries the swept temporary suffix; a failed cleanup must not turn a
			// capability answer into a storage failure.
		}
	}

	private static void enforce(Path normalized) throws IOException {
		PosixFileAttributeView posix = Files.getFileAttributeView(normalized, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posix != null) {
			if (disjoint(posix.readAttributes().permissions(), WRITE_PERMISSIONS)) return;
			try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
				permissions.addAll(posix.readAttributes().permissions());
				permissions.removeAll(WRITE_PERMISSIONS);
				posix.setPermissions(permissions);
				if (!disjoint(posix.readAttributes().permissions(), WRITE_PERMISSIONS)) throw new UnenforceableReadOnlyException("Filesystem did not make immutable file read-only: " + normalized);
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
				if (!isAclProtected(normalized, acl)) throw new UnenforceableReadOnlyException("Filesystem did not protect immutable file ACL: " + normalized);
				channel.force(true);
			}
			return;
		}
		if (dos != null) {
			if (dos.readAttributes().isReadOnly()) return;
			try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				dos.setReadOnly(true);
				if (!dos.readAttributes().isReadOnly()) throw new UnenforceableReadOnlyException("Filesystem did not make immutable file read-only: " + normalized);
				channel.force(true);
			}
			return;
		}
		try {
			if (!normalized.toFile().setReadOnly()) throw new UnenforceableReadOnlyException("Filesystem has no enforceable read-only policy for immutable file: " + normalized);
			if (!blocksWrites(normalized)) throw new UnenforceableReadOnlyException("Filesystem did not enforce its read-only policy for immutable file: " + normalized);
		} catch (UnsupportedOperationException | IllegalArgumentException e) {
			throw new UnenforceableReadOnlyException("Filesystem has no enforceable read-only policy for immutable file: " + normalized, e);
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

	/** Deletes an immutable name, dropping the read-only policy only when the filesystem refuses the delete. */
	public static boolean deleteIfExists(Path file) throws IOException {
		try {
			return Files.deleteIfExists(file);
		} catch (AccessDeniedException denied) {
			if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
			unprotect(file);
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

	/** The filesystem accepted the call and then failed to actually make the file read-only. */
	static final class UnenforceableReadOnlyException extends IOException {
		UnenforceableReadOnlyException(String message) {
			super(message);
		}

		UnenforceableReadOnlyException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
