package pl.skidam.automodpack_core.modpack.group;

import java.nio.file.Path;

public final class LogicalPath {
	private LogicalPath() {}

	public static String normalize(String path) {
		if (path == null || path.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid logical path");
		String normalized = path.replace('\\', '/');
		while (normalized.startsWith("/")) normalized = normalized.substring(1);
		if (normalized.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("Unsafe logical path: " + path);
		Path value = Path.of(normalized).normalize();
		if (value.isAbsolute() || normalized.isBlank() || value.startsWith("..")) throw new IllegalArgumentException("Unsafe logical path: " + path);
		return value.toString().replace('\\', '/');
	}

	public static String requireCanonical(String path) {
		String normalized = normalize(path);
		if (!normalized.equals(path)) throw new IllegalArgumentException("Logical path is not canonical: " + path);
		return normalized;
	}
}
