package pl.skidam.automodpack_core.utils;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Shared operating-system detection and per-user data-directory policy. */
public final class PlatformUtils {

	private static final String OS_NAME = System.getProperty("os.name", "generic").toLowerCase(Locale.ROOT);
	private static final String JAVA_VENDOR = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
	private static final String JAVA_VM_NAME = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
	private static final OperatingSystem OPERATING_SYSTEM = classify(OS_NAME, JAVA_VENDOR, JAVA_VM_NAME);

	public enum OperatingSystem {
		WINDOWS,
		MACOS,
		LINUX,
		ANDROID
	}

	private PlatformUtils() {}

	public static OperatingSystem operatingSystem() {
		return OPERATING_SYSTEM;
	}

	static OperatingSystem classify(String osName, String javaVendor, String javaVmName) {
		String os = Objects.requireNonNull(osName, "OS name").toLowerCase(Locale.ROOT);
		String vendor = Objects.requireNonNull(javaVendor, "Java vendor").toLowerCase(Locale.ROOT);
		String vm = Objects.requireNonNull(javaVmName, "Java VM name").toLowerCase(Locale.ROOT);
		if (vendor.contains("android") || vm.contains("dalvik") || vm.contains("lemur")) return OperatingSystem.ANDROID;
		if (os.contains("mac") || os.contains("darwin")) return OperatingSystem.MACOS;
		if (os.contains("win")) return OperatingSystem.WINDOWS;
		return OperatingSystem.LINUX;
	}

	/** Returns the platform-specific per-user data directory before the application name is appended. */
	public static Path userDataDirectory() {
		Path home = Path.of(System.getProperty("user.home", "."));
		if (OPERATING_SYSTEM == OperatingSystem.WINDOWS) {
			String localAppData = System.getenv("LOCALAPPDATA");
			return localAppData == null || localAppData.isBlank() ? home.resolve("AppData").resolve("Local") : Path.of(localAppData);
		}
		if (OPERATING_SYSTEM == OperatingSystem.MACOS) return home.resolve("Library").resolve("Application Support");
		String xdgDataHome = System.getenv("XDG_DATA_HOME");
		return xdgDataHome == null || xdgDataHome.isBlank() ? home.resolve(".local").resolve("share") : Path.of(xdgDataHome);
	}
}
