package pl.skidam.automodpack_core.modpack.group;

import java.nio.file.Path;

public final class LogicalPath {
	private LogicalPath() {}

	public static String normalize(String path) {
		if (path == null || path.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid logical path");
		String normalized = path.replace('\\', '/');
		while (normalized.startsWith("/")) normalized = normalized.substring(1);
		if (hasDrivePrefix(normalized)) throw new IllegalArgumentException("Unsafe logical path: " + path);
		Path value = Path.of(normalized).normalize();
		if (value.isAbsolute() || normalized.isBlank() || value.startsWith("..")) throw new IllegalArgumentException("Unsafe logical path: " + path);
		return value.toString().replace('\\', '/');
	}

	/** ASCII letters only, matching the old {@code ^[A-Za-z]:.*} regex exactly; {@link Character#isLetter} would wrongly admit non-ASCII letters. */
	private static boolean hasDrivePrefix(String normalized) {
		if (normalized.length() < 2) return false;
		char first = normalized.charAt(0);
		return ((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z')) && normalized.charAt(1) == ':';
	}

	public static String requireCanonical(String path) {
		String normalized = normalize(path);
		if (!normalized.equals(path)) throw new IllegalArgumentException("Logical path is not canonical: " + path);
		return normalized;
	}

	/** Resolves a canonical logical path without allowing it to escape {@code root}. */
	public static Path resolve(Path root, String logicalPath) {
		Path normalizedRoot = root.normalize();
		Path resolved = normalizedRoot.resolve(requireCanonical(logicalPath)).normalize();
		if (!resolved.startsWith(normalizedRoot)) throw new IllegalArgumentException("Logical path escapes its root: " + logicalPath);
		return resolved;
	}
}
