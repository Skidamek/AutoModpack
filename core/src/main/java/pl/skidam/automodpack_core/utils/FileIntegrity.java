package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/**
 * Whether a regular file is the expected bytes. Callers name the question; this module owns cache vs
 * full-read. {@link HashUtils#getHash(Path)} is the digest primitive, not a public identity seam.
 */
public final class FileIntegrity {
	private FileIntegrity() {}

	public static boolean matches(Path file, long expectedSize, String expectedSha1) {
		return matches(file, expectedSize, expectedSha1, null);
	}

	public static boolean matches(Path file, long expectedSize, String expectedSha1, FileMetadataCache cache) {
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
	public static String identityHash(Path file, FileMetadataCache cache) {
		if (cache != null) return cache.getHashOrNull(file);
		return HashUtils.getHash(file);
	}

	/**
	 * Whether a named immutable object is still the advertised bytes. Never reads file content.
	 * With a cache this is the Git-stat tripwire; without one, a regular file of the advertised size.
	 */
	public static boolean matchesNamed(Path file, long expectedSize, String expectedSha1, FileMetadataCache cache) {
		if (!HashUtils.isSha1(expectedSha1) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
		try {
			if (Files.size(file) != expectedSize) return false;
			if (cache != null) return cache.matchesImmutable(file, expectedSize, expectedSha1);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
