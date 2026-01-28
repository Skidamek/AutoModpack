package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import java.util.Locale;
import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.COMPRESSION_ZSTD;

public class PlatformUtils {

    public static final boolean IS_MAC;
    public static final boolean IS_WIN;

    static {
        String os = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
        IS_MAC = os.contains("mac");
        IS_WIN = os.contains("win");
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