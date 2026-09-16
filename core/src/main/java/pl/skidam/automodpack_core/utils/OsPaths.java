package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * OS publishable-path tripwire for durable AutoModpack writes.
 *
 * <p>
 * Receipts: Win32 classic {@code MAX_PATH} is 260 WCHARs including the terminating NUL (MSDN "Maximum File Path
 * Limitation"), so a usable path string is 259 characters, and stock Windows refuses longer paths. When the machine
 * opts into long paths ({@code LongPathsEnabled} registry value, MSDN "Enable long paths in Windows 10, version
 * 1607, and later"), the extended-length maximum is approximately 32,767 characters. POSIX {@code NAME_MAX} and the
 * Windows component length are 255 on every volume ({@code lpMaximumComponentLength}). macOS {@code PATH_MAX} is
 * 1024; Linux is 4096. The JVM's own NIO opens long paths through the {@code \\?\} prefix either way, but the
 * surrounding ecosystem (Explorer, shells, user tooling) cannot until the registry opt-in, so that opt-in is what
 * the Windows budget follows.
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
	/** Usable characters in a classic Win32 path string (MAX_PATH minus NUL). */
	public static final int WINDOWS_USABLE_PATH = WINDOWS_MAX_PATH - 1;
	/** MSDN extended-length maximum (approximate), minus the same NUL overhead as the classic budget. */
	public static final int WINDOWS_LONG_USABLE_PATH = 32767 - 1;
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

	/**
	 * Windows refuses these device names before any extension, so con.txt is as uncreatable as con; the stem before the first dot is
	 * what gets compared, case-insensitively. Receipt: Microsoft's reserved-name list ("Naming Files, Paths, and Namespaces"),
	 * which since the Windows 11 docs update includes COM0 and LPT0 alongside COM1-9 and LPT1-9.
	 */
	private static final Set<String> RESERVED_WINDOWS_DEVICE_NAMES = Set.of("con", "prn", "aux", "nul", "com0", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
			"lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

	/** Whether one path component uses a name Windows reserves for devices, whatever its extension. */
	public static boolean isReservedWindowsDeviceName(String component) {
		int extension = component.indexOf('.');
		String stem = extension < 0 ? component : component.substring(0, extension);
		return RESERVED_WINDOWS_DEVICE_NAMES.contains(stem.toLowerCase(Locale.ROOT));
	}

	public static void requirePublishableFile(Path path) throws IOException {
		requirePublishableFile(path, VERIFIED_TEMP_OVERHEAD);
	}

	public static void requirePublishableConfig(Path path) throws IOException {
		requirePublishableFile(path, CONFIG_TEMP_OVERHEAD);
	}

	public static void requirePublishableDirectory(Path path) throws IOException {
		requirePublishable(path, PlatformUtils.operatingSystem(), 0);
	}

	/**
	 * The absolute normalized parent of one publish target, created when missing. The target itself must pass
	 * {@link #requirePublishableFile}; {@code description} names the target kind in the no-parent failure message.
	 */
	public static Path requirePublishableParent(Path target, String description) throws IOException {
		Objects.requireNonNull(target, "target");
		Path parent = target.toAbsolutePath().normalize().getParent();
		if (parent == null) throw new IOException(description + " has no parent: " + target);
		requirePublishableFile(target);
		Files.createDirectories(parent);
		return parent;
	}

	public static void requirePublishableFile(Path path, int tempFilenameOverhead) throws IOException {
		requirePublishable(path, PlatformUtils.operatingSystem(), tempFilenameOverhead);
	}

	static void requirePublishable(Path path, PlatformUtils.OperatingSystem os, int tempFilenameOverhead) throws IOException {
		boolean classicBudgetWouldOverflow = os == PlatformUtils.OperatingSystem.WINDOWS && nativeString(path, os).length() + tempFilenameOverhead > WINDOWS_USABLE_PATH;
		requirePublishable(path, os, tempFilenameOverhead, classicBudgetWouldOverflow && WindowsLongPaths.areEnabled());
	}

	static void requirePublishable(Path path, PlatformUtils.OperatingSystem os, int tempFilenameOverhead, boolean longPathsEnabled) throws IOException {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(os, "operating system");
		if (tempFilenameOverhead < 0) throw new IllegalArgumentException("Publication temp overhead is negative");
		Path absolute = path.toAbsolutePath().normalize();
		String nativePath = nativeString(absolute, os);
		int maxPath = maxPath(os, longPathsEnabled);
		int publishedLength = nativePath.length() + tempFilenameOverhead;
		if (publishedLength > maxPath) {
			String hint = os == PlatformUtils.OperatingSystem.WINDOWS && !longPathsEnabled ? ". Enable Win32 long paths (LongPathsEnabled) or use a shorter game path" : "";
			throw new IOException("Path exceeds the " + os.name() + " publishable limit of " + maxPath + " characters (path is " + nativePath.length() + ", publication temp adds "
					+ tempFilenameOverhead + ")" + hint + ": " + absolute);
		}
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

	static int maxPath(PlatformUtils.OperatingSystem os, boolean longPathsEnabled) {
		return switch (os) {
			case WINDOWS -> longPathsEnabled ? WINDOWS_LONG_USABLE_PATH : WINDOWS_USABLE_PATH;
			case MACOS -> MACOS_USABLE_PATH;
			case LINUX, OTHER -> LINUX_USABLE_PATH;
		};
	}
}
