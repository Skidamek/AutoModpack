package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.COMPRESSION_ZSTD;

import java.util.Collection;
import java.util.Locale;

import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;

public class PlatformUtils {

	public enum OS {
		WINDOWS, MACOS, LINUX, ANDROID, UNKNOWN
	}

	public static final boolean IS_MAC;
	public static final boolean IS_WIN;
	public static final boolean IS_LINUX;
	private static final OS CURRENT_OS;

	static {
		String os = System.getProperty("os.name", "generic").toLowerCase(Locale.ROOT);
		IS_MAC = os.contains("mac");
		IS_WIN = os.contains("win");
		// Android reports "linux" too, so it has to be ruled out first.
		boolean isAndroid = os.contains("android") || System.getProperty("java.vm.vendor", "").toLowerCase(Locale.ROOT).contains("android");
		IS_LINUX = !isAndroid && (os.contains("nix") || os.contains("nux") || os.contains("aix"));

		if (isAndroid) CURRENT_OS = OS.ANDROID;
		else if (IS_WIN) CURRENT_OS = OS.WINDOWS;
		else if (IS_MAC) CURRENT_OS = OS.MACOS;
		else if (IS_LINUX) CURRENT_OS = OS.LINUX;
		else CURRENT_OS = OS.UNKNOWN;
	}

	public static OS getCurrentOS() {
		return CURRENT_OS;
	}

	/**
	 * Evaluates a group's compatibleOS rules. Empty means every OS. Entries are OS names, optionally
	 * prefixed with '!' to exclude. Any negated entry matching the current OS rejects outright;
	 * otherwise, when positive entries exist, one of them must match.
	 */
	public static boolean isCompatibleWithCurrentOS(Collection<String> compatibleOS) {
		if (compatibleOS == null || compatibleOS.isEmpty()) return true;

		boolean sawPositive = false;
		boolean matchedPositive = false;

		for (String entry : compatibleOS) {
			if (entry == null || entry.isBlank()) continue;
			String trimmed = entry.trim();
			boolean negated = trimmed.startsWith("!");
			if (negated) trimmed = trimmed.substring(1).trim();

			OS target;
			try {
				target = OS.valueOf(trimmed.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				LOGGER.warn("Unknown OS name in compatibleOS: {}", entry);
				continue;
			}

			if (negated) {
				if (CURRENT_OS == target) return false;
			} else {
				sawPositive = true;
				if (CURRENT_OS == target) matchedPositive = true;
			}
		}

		return !sawPositive || matchedPositive;
	}

	// Lazy load
	private static Boolean zstd;

	public static boolean canUseZstd() {
		if (zstd != null) return zstd;

		synchronized (PlatformUtils.class) {
			if (zstd != null) return zstd;
			try {
				CompressionCodec compressionCodec = CompressionFactory.getCodec(COMPRESSION_ZSTD);
				zstd = compressionCodec.isInitialized();
			} catch (Throwable e) {
				zstd = false;
				LOGGER.warn("Desired compression codec failed to initialize, falling back to Gzip");
			}
			return zstd;
		}
	}
}
