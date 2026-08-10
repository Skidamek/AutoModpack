package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Content verification for regular files managed by AutoModpack. */
public final class FileIntegrity {
	private FileIntegrity() {}

	public static boolean matches(Path file, long expectedSize, String expectedSha1) {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
		try {
			return Files.size(file) == expectedSize && expectedSha1.equalsIgnoreCase(HashUtils.getHash(file));
		} catch (IOException e) {
			return false;
		}
	}

	/** Returns whether a regular non-symlink file has the exact canonical SHA-1. */
	public static boolean matchesCanonicalSha1(Path file, String expectedSha1) {
		return HashUtils.isCanonicalSha1(expectedSha1) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && expectedSha1.equals(HashUtils.getHash(file));
	}
}
