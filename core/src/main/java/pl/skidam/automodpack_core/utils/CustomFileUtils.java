package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.config.Jsons;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class CustomFileUtils {
    // TODO change this dummy byte array to contain also some metadata that we have created it
    private static final byte[] smallDummyJar = {
            80, 75, 3, 4, 20, 0, 8, 8, 8, 0, 89, 116, -44, 86, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 20, 0, 4, 0, 77, 69, 84,
            65, 45, 73, 78, 70, 47, 77, 65, 78, 73, 70, 69, 83, 84, 46, 77,
            70, -2, -54, 0, 0, -13, 77, -52, -53, 76, 75, 45, 46, -47, 13, 75,
            45, 42, -50, -52, -49, -77, 82, 48, -44, 51, -32, -27, -30, -27, 2, 0,
            80, 75, 7, 8, -78, 127, 2, -18, 27, 0, 0, 0, 25, 0, 0, 0,
            80, 75, 1, 2, 20, 0, 20, 0, 8, 8, 8, 0, 89, 116, -44, 86,
            -78, 127, 2, -18, 27, 0, 0, 0, 25, 0, 0, 0, 20, 0, 4, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 77, 69,
            84, 65, 45, 73, 78, 70, 47, 77, 65, 78, 73, 70, 69, 83, 84, 46,
            77, 70, -2, -54, 0, 0, 80, 75, 5, 6, 0, 0, 0, 0, 1, 0,
            1, 0, 70, 0, 0, 0, 97, 0, 0, 0, 0, 0,
    };

    private static final Path CWD = Path.of(System.getProperty("user.dir"));

    public static void forceDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }

        if (Files.isRegularFile(file)) {
            dummyIT(file);
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

    // our implementation of Files.copy, thanks to usage of RandomAccessFile we can copy files that are in use
    public static void copyFile(Path source, Path destination) throws IOException {
        setupFilePaths(destination);

        try (RandomAccessFile sourceFile = new RandomAccessFile(source.toFile(), "r");
             FileOutputStream destinationFile = new FileOutputStream(destination.toFile())) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = sourceFile.read(buffer)) != -1) {
                destinationFile.write(buffer, 0, bytesRead);
            }

            destinationFile.flush();
        } catch (IOException e) {
            e.printStackTrace();
//            throw new IOException("Failed to copy file: " + source + " to: " + destination, e);
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

    private static boolean compareFilesByteByByte(Path path, byte[] referenceBytes) {
        try {
            long fileSize = Files.size(path);

            if (fileSize != referenceBytes.length) {
                return false;
            }

            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {

                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = raf.read(buffer)) != -1) {
                    for (int i = 0; i < bytesRead; i++) {
                        byte fileByte = buffer[i];
                        byte referenceByte = referenceBytes[i];

                        if (fileByte != referenceByte) {
                            return false;
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        return true;
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
        if (modpackFileStrAbs.contains(modpackPathStrAbs)) {
            formattedFile = modpackFileStrAbs.replace(modpackPathStrAbs, "");
        } else if (modpackFileStrAbs.contains(cwdStrAbs)) {
            formattedFile = modpackFileStrAbs.replace(cwdStrAbs, "");
        } else if (!modpackFileStrAbs.equals(modpackFileStr)) { // possible in e.g. docker
            LOGGER.error("File: {} ({}) is not in modpack directory: {} ({}) or current working directory: {}", modpackFileStr, modpackFileStrAbs, modpackPath, modpackPathStrAbs, cwdStrAbs);
        }

        formattedFile =  formattedFile.replace(File.separator, "/");

        // Its probably useless, but just in case
        if (!formattedFile.startsWith("/")) {
            formattedFile = "/" + formattedFile;
        }

        return formattedFile;
    }


    public static void deleteDummyFiles(Path directory, Set<Jsons.ModpackContentFields.ModpackContentItem> ignoreList) {
        if (directory == null || ignoreList == null) {
            return;
        }

        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(path -> !shouldIgnore(path, ignoreList))
                    .forEach(path -> {
                        if (compareFilesByteByByte(path, smallDummyJar)) {
                            CustomFileUtils.forceDelete(path);
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static boolean shouldIgnore(Path file, Set<Jsons.ModpackContentFields.ModpackContentItem> ignoreList) {
        if (ignoreList == null) {
            return false;
        }

        String modpackFile = CustomFileUtils.formatPath(file, Objects.requireNonNullElse(selectedModpackDir, hostContentModpackDir));

        for (Jsons.ModpackContentFields.ModpackContentItem item : ignoreList) {
            if (item.file.equals(modpackFile)) {
                return true;
            }
        }

        return false;
    }

    // our trick for not able to just delete file on specific filesystem (windows...)
    public static void dummyIT(Path file) {
        try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
            fos.write(smallDummyJar);
            fos.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String getHash(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;

            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = raf.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hash = digest.digest();
            return HexFormat.of().formatHex(hash);
        } catch (UnsupportedOperationException e) {
            try { // yes... its awful
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                try (var is = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        digest.update(buffer, 0, bytesRead);
                    }
                }

                byte[] hash = digest.digest();
                return HexFormat.of().formatHex(hash);
            } catch (Exception ex) {
                e.printStackTrace();
                ex.printStackTrace();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get hash of file: {}", file, e);
        }
        return null;
    }

    public static String getCurseforgeMurmurHash(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }

        final int m = 0x5bd1e995;
        final int r = 24;
        long k = 0x0L;
        int seed = 1;
        int shift = 0x0;

        long length = 0;
        char b;

        // Read file in 8128-byte chunks
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] buffer = new byte[8128];
            int bytesRead;

            while ((bytesRead = raf.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    b = (char) buffer[i];

                    if (b == 0x9 || b == 0xa || b == 0xd || b == 0x20) {
                        continue;
                    }

                    length += 1;
                }
            }
        }

        long h = (seed ^ length);

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] buffer = new byte[8128];
            int bytesRead;

            while ((bytesRead = raf.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    b = (char) buffer[i];

                    if (b == 0x9 || b == 0xa || b == 0xd || b == 0x20) {
                        continue;
                    }

                    if (b > 255) {
                        while (b > 255) {
                            b -= 255;
                        }
                    }

                    k = k | ((long) b << shift);

                    shift = shift + 0x8;

                    if (shift == 0x20) {
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

                        k = 0x0;
                        shift = 0x0;
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


    public static boolean hashCompare(Path file1, Path file2) {
        if (!Files.exists(file1) || !Files.exists(file2)) return false;

        String hash1 = getHash(file1);
        String hash2 = getHash(file2);

        if (hash1 == null || hash2 == null) return false;

        return hash1.equals(hash2);
    }

    public static boolean isEmptyDirectory(Path parentPath) throws IOException {
        if (!Files.isDirectory(parentPath)) return false;
        try (Stream<Path> pathStream = Files.list(parentPath)) {
            return pathStream.findAny().isEmpty();
        }
    }
}
