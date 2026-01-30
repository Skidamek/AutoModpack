package pl.skidam.automodpack_core.utils;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.stream.Stream;

import static pl.skidam.automodpack_core.Constants.*;

public class SmartFileUtils {

    public static final Path CWD = Path.of(System.getProperty("user.dir"));

    // --- File Operations (Delete / Copy / Move) ---

    public static void executeOrder66(Path file) {
        executeOrder66(file, true);
    }

    public static void executeOrder66(Path file, boolean saveDummyFiles) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) { }

        if (Files.isRegularFile(file)) {
            LegacyClientCacheUtils.dummyIT(file);
            if (saveDummyFiles) {
                LegacyClientCacheUtils.saveDummyFiles();
            }
        }
    }

    public static void hardlinkFile(Path sourceFile, Path targetFile) throws IOException {
        createParentDirs(targetFile);
        try {
            Files.createLink(targetFile, sourceFile);
        } catch (IOException e) {
            LOGGER.warn("Failed to create hardlink from {} to {}, falling back to copy", sourceFile, targetFile, e);
            copyFile(sourceFile, targetFile);
        }
    }

    public static void copyFile(Path sourceFile, Path targetFile) throws IOException {
        createParentDirs(targetFile);

        // Use a temp file to ensure atomicity at the destination
        Path tempTargetFile = targetFile.resolveSibling(targetFile.getFileName() + ".tmp_" + System.nanoTime());

        try {
            // Copy Source -> Temp
            performSmartCopy(sourceFile, tempTargetFile);
            // Promote Temp -> Target
            moveFile(tempTargetFile, targetFile);
        } catch (Exception e) {
            try { Files.deleteIfExists(tempTargetFile); } catch (IOException ignored) {}
            LOGGER.error("Failed to copy file from {} to {}", sourceFile, targetFile, e);
            throw e;
        }
    }

    public static void moveFile(Path sourceFile, Path targetFile) throws IOException {
        try {
            // Atomic Move: The gold standard for consistency
            Files.move(sourceFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                // Fallback: Standard Move
                Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                // Last Resort: Copy & Delete (Required for cross-drive moves)
                performSmartCopy(sourceFile, targetFile);
                Files.deleteIfExists(sourceFile);
            }
        }
    }

    private static void performSmartCopy(Path source, Path target) throws IOException {
        try {
            // Try Native reflink (CoW) on Java 20+
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Fallback to Zero-Copy Channel Transfer (Handles locked files on Windows)
            copyViaChannel(source, target);
        }
    }

    private static void copyViaChannel(Path sourceFile, Path targetFile) throws IOException {
        try (FileChannel source = LockFreeInputStream.openChannel(sourceFile);
             FileChannel target = FileChannel.open(targetFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            long count = source.size();
            long position = 0;
            while (position < count) {
                position += source.transferTo(position, count - position, target);
            }
        }
    }

    // --- Directory & Path Logic ---

    public static void createParentDirs(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    public static boolean isEmptyDirectory(Path parentPath) throws IOException {
        if (!Files.isDirectory(parentPath)) return false;
        try (Stream<Path> pathStream = Files.list(parentPath)) {
            return pathStream.findAny().isEmpty();
        }
    }

    public static boolean compareSmallFile(Path path, byte[] referenceBytes) {
        try {
            if (Files.size(path) != referenceBytes.length) return false;
            try (InputStream is = new LockFreeInputStream(path)) {
                return Arrays.equals(is.readNBytes(referenceBytes.length), referenceBytes);
            }
        } catch (Exception e) {
            LOGGER.error("Error comparing file: {}", path, e);
            return false;
        }
    }

    public static Path getPathFromCWD(String path) {
        return getPath(CWD, path);
    }

    public static Path getPath(Path origin, String path) {
        if (origin == null) throw new IllegalArgumentException("Origin path must not be null");
        if (path == null || path.isBlank()) return origin;

        if (path.indexOf('\\') >= 0) path = path.replace('\\', '/');
        if (path.startsWith("/")) path = path.substring(1);

        return origin.resolve(path).normalize();
    }

    public static String formatPath(final Path modpackFile, final Path modpackPath) {
        if (modpackPath == null || modpackFile == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }

        String modpackFileStrAbs = modpackFile.toAbsolutePath().normalize().toString();
        String modpackPathStrAbs = modpackPath.toAbsolutePath().normalize().toString();
        String cwdStrAbs = CWD.toAbsolutePath().normalize().toString();

        String formattedFile = modpackFile.normalize().toString();

        if (modpackFileStrAbs.startsWith(modpackPathStrAbs)) {
            formattedFile = modpackFileStrAbs.substring(modpackPathStrAbs.length());
        } else if (modpackFileStrAbs.startsWith(cwdStrAbs)) {
            formattedFile = modpackFileStrAbs.substring(cwdStrAbs.length());
        }

        formattedFile = formattedFile.replace(File.separator, "/");
        return formattedFile.startsWith("/") ? formattedFile : "/" + formattedFile;
    }
}