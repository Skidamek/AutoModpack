package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * OS publishable-path tripwire for durable AutoModpack writes.
 *
 * <p>
 * Receipts: Win32 {@code MAX_PATH} is 260 WCHARs including the terminating NUL (MSDN {@code CreateFile}), so a usable
 * path string is 259 characters. Classic Windows APIs refuse longer paths unless the process opts into long paths;
 * Minecraft launchers typically do not. POSIX {@code NAME_MAX} and Windows component length are 255. macOS
 * {@code PATH_MAX} is 1024; Linux is 4096.
 * </p>
 *
 * <p>
 * Publication temps sit next to the target: {@link VerifiedFileTransfer} uses
 * {@code "." + filename + "." + Long.toString + ".tmp"} (max 19-digit long → +25 on the file name);
 * {@link pl.skidam.automodpack_core.config.ConfigTools} uses {@code "." + filename + "." + UUID + ".tmp"} (+42). The
 * tripwire budgets that overhead so the temporary, not only the final path, stays inside the OS limit.
 * </p>
 */
public final class OsPaths {
	/** MSDN MAX_PATH including NUL. */
	public static final int WINDOWS_MAX_PATH = 260;
	/** Usable characters in a Win32 path string (MAX_PATH minus NUL). */
	public static final int WINDOWS_USABLE_PATH = WINDOWS_MAX_PATH - 1;
	/** Windows / POSIX file-name component limit. */
	public static final int MAX_COMPONENT = 255;
	/** {@code File.createTempFile} appends {@code Long.toString(Math.abs(n))}; Long.MAX_VALUE is 19 digits. */
	public static final int TEMP_RANDOM_DIGITS = 19;
	/** {@code "." + filename + "." + digits + ".tmp"} minus the original filename. */
	public static final int VERIFIED_TEMP_OVERHEAD = 1 + 1 + TEMP_RANDOM_DIGITS + 4;
	/** {@code "." + filename + "." + UUID + ".tmp"}; UUID.toString is 36 characters. */
	public static final int CONFIG_TEMP_OVERHEAD = 1 + 1 + 36 + 4;
	private static final int LINUX_USABLE_PATH = 4096 - 1;
	private static final int MACOS_USABLE_PATH = 1024 - 1;

	private OsPaths() {}

	public static void requirePublishableFile(Path path) throws IOException {
		requirePublishableFile(path, VERIFIED_TEMP_OVERHEAD);
	}

	public static void requirePublishableConfig(Path path) throws IOException {
		requirePublishableFile(path, CONFIG_TEMP_OVERHEAD);
	}

	public static void requirePublishableDirectory(Path path) throws IOException {
		requirePublishable(path, PlatformUtils.operatingSystem(), 0);
	}

	public static void requirePublishableFile(Path path, int tempFilenameOverhead) throws IOException {
		requirePublishable(path, PlatformUtils.operatingSystem(), tempFilenameOverhead);
	}

	static void requirePublishable(Path path, PlatformUtils.OperatingSystem os, int tempFilenameOverhead) throws IOException {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(os, "operating system");
		if (tempFilenameOverhead < 0) throw new IllegalArgumentException("Publication temp overhead is negative");
		Path absolute = path.toAbsolutePath().normalize();
		String nativePath = nativeString(absolute, os);
		int maxPath = maxPath(os);
		int publishedLength = nativePath.length() + tempFilenameOverhead;
		if (publishedLength > maxPath)
			throw new IOException("Path exceeds the " + os.name() + " publishable limit of " + maxPath + " characters (path is " + nativePath.length() + ", publication temp adds "
					+ tempFilenameOverhead + "): " + absolute);
		int count = absolute.getNameCount();
		for (int index = 0; index < count; index++) {
			String component = absolute.getName(index).toString();
			int extra = index == count - 1 ? tempFilenameOverhead : 0;
			if (component.length() + extra > MAX_COMPONENT)
				throw new IOException("Path component exceeds the " + MAX_COMPONENT + " character limit (component is " + component.length() + ", publication temp adds " + extra + "): "
						+ absolute);
		}
	}

	static String nativeString(Path path, PlatformUtils.OperatingSystem os) {
		String value = path.toAbsolutePath().normalize().toString();
		return os == PlatformUtils.OperatingSystem.WINDOWS ? value.replace('/', '\\') : value;
	}

	static int maxPath(PlatformUtils.OperatingSystem os) {
		return switch (os) {
			case WINDOWS -> WINDOWS_USABLE_PATH;
			case MACOS -> MACOS_USABLE_PATH;
			case LINUX, OTHER -> LINUX_USABLE_PATH;
		};
	}
}
