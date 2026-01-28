package pl.skidam.automodpack_core.utils;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

import static pl.skidam.automodpack_core.Constants.*;

public class SmartFileUtils {

    public static final Path CWD = Path.of(System.getProperty("user.dir"));

    public static void executeOrder66(Path file) {
        executeOrder66(file, true);
    }

    public static void executeOrder66(Path file, boolean saveDummyFiles) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }

        if (Files.isRegularFile(file)) {
            LegacyClientCacheUtils.dummyIT(file);
            if (saveDummyFiles) {
                LegacyClientCacheUtils.saveDummyFiles();
            }
        }
    }

    public static Path getPathFromCWD(String path) {
        return getPath(CWD, path);
    }

    // Special for use instead of normal resolve, since it wont work because of the leading slash in file
    public static Path getPath(Path origin, String path) {
        if (origin == null) {
            throw new IllegalArgumentException("Origin path must not be null");
        }
        if (path == null || path.isBlank()) {
            return origin;
        }

        path = path.replace('\\', '/');

        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        return origin.resolve(path).normalize();
    }

    public static boolean isFilePhysical(Path path) {
        return path.getFileSystem() == FileSystems.getDefault();
    }

    public static void copyFile(Path source, Path destination) throws IOException {
        setupFilePaths(destination);

        try (InputStream is = new LockFreeInputStream(source);
             OutputStream os = Files.newOutputStream(destination,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {

            is.transferTo(os);
        } catch (IOException e) {
            LOGGER.error("Failed to copy a file from {} to {}", source, destination);
        }
    }

    public static void setupFilePaths(Path file) throws IOException {
        if (!Files.exists(file)) {
            if (!Files.exists(file.getParent())) {
                Files.createDirectories(file.getParent());
            }
            // Windows? #302
//            Files.createFile(destination);
            file.toFile().createNewFile();
        }
    }

    public static boolean compareSmallFile(Path path, byte[] referenceBytes) {
        try {
            if (Files.size(path) != referenceBytes.length) {
                return false;
            }

            try (InputStream is = new LockFreeInputStream(path)) {
                // Java 11+ readNBytes reads exactly X bytes or until EOF.
                // Since we know the file is small (~200b), reading it into RAM is perfectly fine.
                byte[] fileContent = is.readNBytes(referenceBytes.length);

                // Vectorized Comparison (AVX optimized in Java 17)
                return Arrays.equals(fileContent, referenceBytes);
            }
        } catch (Exception e) {
            LOGGER.error("Error comparing file: {}", path, e);
            return false;
        }
    }

    // Formats path to be relative to the modpack directory - modpack-content format
    // arguments can not be null
    public static String formatPath(final Path modpackFile, final Path modpackPath) {
        if (modpackPath == null || modpackFile == null) {
            throw new IllegalArgumentException("Arguments are null - modpackPath: " + modpackPath + ", modpackFile: " + modpackFile);
        }

        final String modpackFileStr = modpackFile.normalize().toString();
        final String modpackFileStrAbs = modpackFile.toAbsolutePath().normalize().toString();
        final String modpackPathStrAbs = modpackPath.toAbsolutePath().normalize().toString();
        final String cwdStrAbs = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();

        String formattedFile = modpackFileStr;

        // Checks if in file parents paths (absolute path) there is modpack directory (absolute path)
        if (modpackFileStrAbs.startsWith(modpackPathStrAbs)) {
            formattedFile = modpackFileStrAbs.substring(modpackPathStrAbs.length());
        } else if (modpackFileStrAbs.startsWith(cwdStrAbs)) {
            formattedFile = modpackFileStrAbs.substring(cwdStrAbs.length());
        } else if (!modpackFileStrAbs.equals(modpackFileStr)) { // possible in e.g. docker
            LOGGER.error("File: {} ({}) is not in modpack directory: {} ({}) or current working directory: {}", modpackFileStr, modpackFileStrAbs, modpackPath, modpackPathStrAbs, cwdStrAbs);
        }

        formattedFile =  formattedFile.replace(File.separator, "/");

        // Its probably useless, but just in case
        formattedFile = prefixSlash(formattedFile);

        return formattedFile;
    }

    public static String prefixSlash(String path) {
        if (!path.isEmpty() && path.charAt(0) == '/') {
            return path;
        }
        return "/" + path;
    }

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
        } catch (IOException ignored) { // we don't really care about this exception, file may just not exists or be a directory
        } catch (Exception e) {
            LOGGER.error("Failed to get hash for path: {}", path, e);
        }
        return null;
    }

    // We do double pass to avoid storing whole file in memory
    public static String getCurseforgeMurmurHash(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }

        // MurmurHash2 Constants
        final int m = 0x5bd1e995;
        final int r = 24;
        final int seed = 1;

        // Pass 1
        // We scan the file just to count non-whitespace bytes
        long validLength = 0;

        byte[] buffer = new byte[64 * 1024];

        try (InputStream is = new LockFreeInputStream(file)) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    byte b = buffer[i];
                    // Check for whitespace (Tab, LF, CR, Space)
                    if (b != 0x9 && b != 0xa && b != 0xd && b != 0x20) {
                        validLength++;
                    }
                }
            }
        }

        // Pass 2
        // Now we have the length, we can initialize 'h' correctly with Bitwise XOR
        long h = (seed ^ validLength);
        long k = 0;
        int shift = 0;

        try (InputStream is = new LockFreeInputStream(file)) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    byte b = buffer[i];

                    // Same filter logic
                    if (b == 0x9 || b == 0xa || b == 0xd || b == 0x20) {
                        continue;
                    }

                    // Append byte to current 4-byte chunk 'k'
                    k = k | ((long) (b & 0xFF) << shift);
                    shift += 8;

                    // If chunk is full (32 bits), mix it into 'h'
                    if (shift == 32) {
                        h = 0x00000000FFFFFFFFL & h;

                        k = k * m;
                        k = 0x00000000FFFFFFFFL & k;

                        k = k ^ (k >> r);
                        k = 0x00000000FFFFFFFFL & k;

                        k = k * m;
                        k = 0x00000000FFFFFFFFL & k;

                        h = h * m;
                        h = 0x00000000FFFFFFFFL & h;

                        h = h ^ k;
                        h = 0x00000000FFFFFFFFL & h;

                        // Reset chunk
                        k = 0;
                        shift = 0;
                    }
                }
            }
        }

        if (shift > 0) {
            h = h ^ k;
            h = 0x00000000FFFFFFFFL & h;

            h = h * m;
            h = 0x00000000FFFFFFFFL & h;
        }

        h = h ^ (h >> 13);
        h = 0x00000000FFFFFFFFL & h;

        h = h * m;
        h = 0x00000000FFFFFFFFL & h;

        h = h ^ (h >> 15);
        h = 0x00000000FFFFFFFFL & h;

        return String.valueOf(h);
    }

    public static boolean isEmptyDirectory(Path parentPath) throws IOException {
        if (!Files.isDirectory(parentPath)) return false;
        try (Stream<Path> pathStream = Files.list(parentPath)) {
            return pathStream.findAny().isEmpty();
        }
    }
}
