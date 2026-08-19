package pl.skidam.automodpack_core.utils;

import java.nio.file.Path;
import java.util.Locale;

/** Shared operating-system detection and per-user data-directory policy. */
public final class PlatformUtils {

	private static final String OS_NAME = System.getProperty("os.name", "generic").toLowerCase(Locale.ROOT);
	private static final String JAVA_VENDOR = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
	private static final String JAVA_VM_NAME = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
	public static final boolean IS_MAC;
	public static final boolean IS_WIN;
	public static final boolean IS_ANDROID;

	static {
		IS_MAC = OS_NAME.contains("mac") || OS_NAME.contains("darwin");
		IS_WIN = OS_NAME.contains("win");
		IS_ANDROID = JAVA_VENDOR.contains("android") || JAVA_VM_NAME.contains("dalvik") || JAVA_VM_NAME.contains("lemur");
	}

	private PlatformUtils() {}

	/** Returns the platform-specific per-user data directory before the application name is appended. */
	public static Path userDataDirectory() {
		Path home = Path.of(System.getProperty("user.home", "."));
		if (IS_WIN) {
			String localAppData = System.getenv("LOCALAPPDATA");
			return localAppData == null || localAppData.isBlank() ? home.resolve("AppData").resolve("Local") : Path.of(localAppData);
		}
		if (IS_MAC) return home.resolve("Library").resolve("Application Support");
		String xdgDataHome = System.getenv("XDG_DATA_HOME");
		return xdgDataHome == null || xdgDataHome.isBlank() ? home.resolve(".local").resolve("share") : Path.of(xdgDataHome);
	}
}
