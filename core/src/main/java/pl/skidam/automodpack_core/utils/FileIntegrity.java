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
}
