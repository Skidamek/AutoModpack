package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * <li>{@link #matchesObject} is published pack bytes: {@code file} is the canonical object's inode, or a
 * distinct named copy answering to the same named tripwire. Projection hardlinks and directory renames stay a
 * stat; worktree identity is a different question and is never asked here.</li>
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
		try {
			return matches(file, expectedSize, expectedSha1, cache, FileCache.statSnapshot(file));
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean matches(Path file, long expectedSize, String expectedSha1, FileCache cache, FileCache.StatSnapshot snapshot) {
		if (!snapshot.isTrackedRegularFile() || snapshot.size() != expectedSize) return false;
		String hash = identityHash(file, cache, snapshot);
		return hash != null && expectedSha1.equalsIgnoreCase(hash);
	}

	/**
	 * Path-keyed identity hash of an existing file. When {@code cache} is present this is a Git-stat
	 * lookup; when it is {@code null} the current bytes are hashed.
	 */
	public static String identityHash(Path file, FileCache cache) {
		if (cache != null) return cache.getHashOrNull(file);
		return HashUtils.getHash(file);
	}

	private static String identityHash(Path file, FileCache cache, FileCache.StatSnapshot snapshot) {
		if (cache != null) return cache.getHashOrNull(file, snapshot);
		return HashUtils.getHash(file);
	}

	/**
	 * Hash of {@code file} for observation. If the named tripwire still agrees with the advertised
	 * identity, that hash is returned and the bytes are not read. Otherwise this is {@link #identityHash}.
	 */
	public static String observedHash(Path file, long expectedSize, String expectedSha1, FileCache cache) {
		try {
			FileCache.StatSnapshot snapshot = FileCache.statSnapshot(file);
			if (matchesNamed(file, expectedSize, expectedSha1, cache, snapshot)) return HashUtils.normalizeSha1(expectedSha1);
			return identityHash(file, cache, snapshot);
		} catch (IOException e) {
			return identityHash(file, cache);
		}
	}

	/**
	 * Whether a named immutable object is still the advertised bytes. With a cache this is the named
	 * tripwire (size, mtime, inode), rehashing only when that fingerprint is disturbed. Without a cache, a
	 * regular file of the advertised size.
	 */
	public static boolean matchesNamed(Path file, long expectedSize, String expectedSha1, FileCache cache) {
		if (!HashUtils.isSha1(expectedSha1)) return false;
		try {
			return matchesNamed(file, expectedSize, expectedSha1, cache, FileCache.statSnapshot(file));
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean matchesNamed(Path file, long expectedSize, String expectedSha1, FileCache cache, FileCache.StatSnapshot snapshot) {
		if (!HashUtils.isSha1(expectedSha1)) return false;
		if (!snapshot.isTrackedRegularFile() || snapshot.size() != expectedSize) return false;
		if (cache == null) return true;
		return cache.matchesImmutable(file, expectedSize, expectedSha1, snapshot);
	}

	/**
	 * Whether {@code file} is the advertised CAS bytes. A hardlink of a still-valid canonical object is
	 * those bytes regardless of path, so a projection rename stays a stat. A distinct copy answers to the
	 * named tripwire, which rehashes only when that tripwire is disturbed; worktree identity would rehash
	 * on the ctime our own {@code link()} and {@code chmod()} bump at every publication.
	 */
	public static boolean matchesObject(Path file, Path canonicalObject, long expectedSize, String expectedSha1, FileCache cache) {
		if (!HashUtils.isSha1(expectedSha1)) return false;
		try {
			FileCache.StatSnapshot fileSnapshot = FileCache.statSnapshot(file);
			try {
				FileCache.StatSnapshot canonical = FileCache.statSnapshot(canonicalObject);
				if (matchesNamed(canonicalObject, expectedSize, expectedSha1, cache, canonical) && sameInode(file, canonicalObject, canonical, fileSnapshot)) return true;
			} catch (IOException e) {
				// A stat failure answers false on the disturbed side; the file snapshot already paid for decides below.
			}
			return matchesNamed(file, expectedSize, expectedSha1, cache, fileSnapshot);
		} catch (IOException e) {
			return false;
		}
	}

	/** Whether two regular non-symlink paths share an inode (Unix file key / NTFS file index). */
	public static boolean sameInode(Path left, Path right) {
		try {
			return sameInode(left, right, FileCache.statSnapshot(left), FileCache.statSnapshot(right));
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean sameInode(Path left, Path right, FileCache.StatSnapshot leftSnapshot, FileCache.StatSnapshot rightSnapshot) throws IOException {
		if (!leftSnapshot.isTrackedRegularFile() || !rightSnapshot.isTrackedRegularFile()) return false;
		if (leftSnapshot.fileKey() != null && rightSnapshot.fileKey() != null) return leftSnapshot.fileKey().equals(rightSnapshot.fileKey());
		return Files.isSameFile(left, right);
	}
}
