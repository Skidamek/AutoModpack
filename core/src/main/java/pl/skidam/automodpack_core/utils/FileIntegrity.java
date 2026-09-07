package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Whether a regular file is the expected bytes. Callers name the question; this module owns cache vs
 * full-read. {@link HashUtils#getHash(Path)} is the digest primitive, not a public identity seam.
 *
 * <p>
 * Two questions, never mixed:
 * <ul>
 * <li>{@link #matches} is Git worktree identity (size, mtime, ctime/ChangeTime, inode, racy mtime). Use it for
 * overlay, editable live files, and user mods that are not a CAS inode.</li>
 * <li>{@link #matchesNamed} is the named-object tripwire (size, mtime, inode). Use it for the canonical CAS
 * path. ctime/ChangeTime is ignored because {@code link()} and {@code chmod()} bump it on the shared inode.</li>
 * <li>{@link #matchesObject} is published pack bytes: the canonical object passes {@link #matchesNamed}, and
 * {@code file} is that inode or a distinct copy that itself passes the named tripwire. Projection hardlinks
 * and directory renames stay a stat.</li>
 * </ul>
 * SHA-1 runs at ingress, when a tripwire is missing or disturbed, and during explicit fsck. A hot-path CAS hit
 * never hashes.
 */
public final class FileIntegrity {
	private FileIntegrity() {}

	public static boolean matches(Path file, long expectedSize, String expectedSha1) {
		return matches(file, expectedSize, expectedSha1, null);
	}

	public static boolean matches(Path file, long expectedSize, String expectedSha1, FileCache cache) {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
		try {
			if (Files.size(file) != expectedSize) return false;
			String hash = identityHash(file, cache);
			return hash != null && expectedSha1.equalsIgnoreCase(hash);
		} catch (IOException e) {
			return false;
		}
	}

	/** Returns whether a regular non-symlink file has the exact canonical SHA-1. */
	public static boolean matchesCanonicalSha1(Path file, String expectedSha1) {
		return HashUtils.isCanonicalSha1(expectedSha1) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && expectedSha1.equals(identityHash(file, null));
	}

	/**
	 * Path-keyed identity hash of an existing file. When {@code cache} is present this is a Git-stat
	 * lookup; when it is {@code null} the current bytes are hashed.
	 */
	public static String identityHash(Path file, FileCache cache) {
		if (cache != null) return cache.getHashOrNull(file);
		return HashUtils.getHash(file);
	}

	/**
	 * Whether a named immutable object is still the advertised bytes. With a cache this is the named
	 * tripwire (size, mtime, inode), rehashing only when that fingerprint is disturbed. Without a cache, a
	 * regular file of the advertised size.
	 */
	public static boolean matchesNamed(Path file, long expectedSize, String expectedSha1, FileCache cache) {
		if (!HashUtils.isSha1(expectedSha1) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
		try {
			if (Files.size(file) != expectedSize) return false;
			if (cache != null) return cache.matchesImmutable(file, expectedSize, expectedSha1);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Whether {@code file} is the advertised CAS bytes. A hardlink of a still-valid canonical object is
	 * those bytes regardless of path, so a projection rename stays a stat. If the file is a distinct copy,
	 * or the canonical object is gone, the named tripwire runs on {@code file} itself.
	 */
	public static boolean matchesObject(Path file, Path canonicalObject, long expectedSize, String expectedSha1, FileCache cache) {
		if (matchesNamed(canonicalObject, expectedSize, expectedSha1, cache) && sameInode(file, canonicalObject)) return true;
		return matchesNamed(file, expectedSize, expectedSha1, cache);
	}

	/** Whether two regular non-symlink paths share an inode (Unix file key / NTFS file index). */
	public static boolean sameInode(Path left, Path right) {
		try {
			if (!Files.isRegularFile(left, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(right, LinkOption.NOFOLLOW_LINKS)) return false;
			BasicFileAttributes leftAttributes = Files.readAttributes(left, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			BasicFileAttributes rightAttributes = Files.readAttributes(right, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (leftAttributes.isSymbolicLink() || rightAttributes.isSymbolicLink()) return false;
			String leftKey = FileCache.fingerprint(left, leftAttributes).fileKey();
			String rightKey = FileCache.fingerprint(right, rightAttributes).fileKey();
			if (leftKey != null && rightKey != null && !"null".equals(leftKey) && !"null".equals(rightKey)) return leftKey.equals(rightKey);
			return Files.isSameFile(left, right);
		} catch (IOException e) {
			return false;
		}
	}
}
