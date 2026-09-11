package pl.skidam.automodpack_core.utils;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Shared operating-system detection and per-user data-directory policy. */
public final class PlatformUtils {

	private static final OperatingSystem OPERATING_SYSTEM = classify(System.getProperty("os.name", ""));

	public enum OperatingSystem {
		WINDOWS,
		MACOS,
		LINUX,
		OTHER
	}

	private PlatformUtils() {}

	public static OperatingSystem operatingSystem() {
		return OPERATING_SYSTEM;
	}

	/**
	 * Recognizes only the first-class desktop systems; every other kernel (the BSDs, Solaris, Android launchers
	 * that do not announce themselves, anything new) lands in {@link OperatingSystem#OTHER}, which knows nothing
	 * about the platform: modpack content resolves to platform-agnostic groups only, and the player can still
	 * pick a concrete platform in the selection screen when they know better. Mobile and other unusual systems
	 * are not probed for on purpose - every launcher reports something different.
	 * Order matters: "darwin" contains "win", so macOS must be tested before Windows.
	 */
	static OperatingSystem classify(String osName) {
		String os = Objects.requireNonNull(osName, "OS name").toLowerCase(Locale.ROOT);
		if (os.contains("mac") || os.contains("darwin")) return OperatingSystem.MACOS;
		if (os.contains("win")) return OperatingSystem.WINDOWS;
		if (os.contains("linux")) return OperatingSystem.LINUX;
		return OperatingSystem.OTHER;
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
