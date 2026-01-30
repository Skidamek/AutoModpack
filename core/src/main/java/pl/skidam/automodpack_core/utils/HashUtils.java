package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public class HashUtils {

    public static String getHash(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream is = new LockFreeInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException ignored) { 
            // File might not exist
        } catch (Exception e) {
            LOGGER.error("Failed to get hash for path: {}", path, e);
        }
        return null;
    }

    /**
     * Calculates the CurseForge specific MurmurHash2.
     * Normalized by ignoring whitespace (0x9, 0xA, 0xD, 0x20).
     */
    public static String getCurseforgeMurmurHash(Path file) throws IOException {
        if (!Files.exists(file)) return null;

        // MurmurHash2 Constants
        final int m = 0x5bd1e995;
        final int r = 24;
        final int seed = 1;

        // Pass 1: Count valid non-whitespace bytes to determine the hash seed
        long validLength = 0;
        byte[] buffer = new byte[64 * 1024];

        try (InputStream is = new LockFreeInputStream(file)) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    if (!isWhitespace(buffer[i])) {
                        validLength++;
                    }
                }
            }
        }

        // Pass 2: Calculate Hash
        long h = (seed ^ validLength);
        long k = 0;
        int shift = 0;

        try (InputStream is = new LockFreeInputStream(file)) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    byte b = buffer[i];
                    if (isWhitespace(b)) continue;

                    // Append byte to current 4-byte chunk
                    k = k | ((long) (b & 0xFF) << shift);
                    shift += 8;

                    if (shift == 32) {
                        k = (k * m) & 0xFFFFFFFFL;
                        k ^= (k >>> r);
                        k = (k * m) & 0xFFFFFFFFL;

                        h = (h * m) & 0xFFFFFFFFL;
                        h ^= k;
                        
                        // Reset chunk
                        k = 0;
                        shift = 0;
                    }
                }
            }
        }

        // Handle tail
        if (shift > 0) {
            h ^= k;
            h = (h * m) & 0xFFFFFFFFL;
        }

        h ^= (h >>> 13);
        h = (h * m) & 0xFFFFFFFFL;
        h ^= (h >>> 15);

        return String.valueOf(h);
    }

    private static boolean isWhitespace(byte b) {
        return b == 0x9 || b == 0xA || b == 0xD || b == 0x20;
    }
}