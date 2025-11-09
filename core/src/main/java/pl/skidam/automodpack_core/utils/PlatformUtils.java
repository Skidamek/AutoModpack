package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;

import java.util.Locale;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.COMPRESSION_ZSTD;

public class PlatformUtils {

    private static Boolean macCache;
    private static Boolean zstdWorks;

    public static boolean isMac() {
        if (macCache != null) return macCache;

        String runtime = System.getProperty("os.name");
        if (runtime != null && runtime.toLowerCase(Locale.ENGLISH).contains("mac")) {
            return macCache = true;
        }

        return macCache = false;
    }

    public static boolean canUseZstd() {
        if (zstdWorks != null) return zstdWorks;

        try {
            CompressionCodec compressionCodec = CompressionFactory.getCodec(COMPRESSION_ZSTD);
            zstdWorks = compressionCodec.isInitialized();
        } catch (Throwable e) {
            zstdWorks = false;
            LOGGER.warn("Desired compression codec failed to initialize, falling back to Gzip");
        }

        return zstdWorks;
    }
}
