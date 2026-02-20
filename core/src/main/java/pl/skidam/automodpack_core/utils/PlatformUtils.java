package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.COMPRESSION_ZSTD;

import java.util.Locale;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;

/**
 * Utility class for platform/OS detection
 */

public class PlatformUtils {

    public enum OS {
        WINDOWS,
        LINUX,
        MACOS,
        UNKNOWN,
    }

    public static final boolean IS_MAC;
    public static final boolean IS_WIN;
    public static final boolean IS_LINUX;
    private static final OS DETECTED_OS;

    static {
        String osName = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
        IS_MAC = osName.contains("mac");
        IS_WIN = osName.contains("win");
        IS_LINUX = osName.contains("linux");

        if (IS_WIN) {
            DETECTED_OS = OS.WINDOWS;
        } else if (IS_MAC) {
            DETECTED_OS = OS.MACOS;
        } else if (IS_LINUX) {
            DETECTED_OS = OS.LINUX;
        } else {
            DETECTED_OS = OS.UNKNOWN;
        }
    }

    /**
     * Get the current operating system
     *
     * @return the detected OS
     */
    public static OS getCurrentOS() {
        return DETECTED_OS;
    }

    /**
     * Check if the current OS matches the given OS name
     *
     * @param osName the OS name to check (case-insensitive, e.g., "windows",
     *               "linux", "macos")
     * @return true if the current OS matches
     */
    public static boolean isCurrentOS(String osName) {
        if (osName == null || osName.isBlank()) {
            return true; // Empty or null means compatible with all
        }
        try {
            OS targetOS = OS.valueOf(osName.toUpperCase());
            return DETECTED_OS == targetOS;
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown OS name: {}", osName);
            return false;
        }
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
