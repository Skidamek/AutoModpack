package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
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

    private static final Jsons.ClientDummyFiles clientDummyFiles = ConfigTools.load(clientDummyFilesFile, Jsons.ClientDummyFiles.class);
    private static final Path CWD = Path.of(System.getProperty("user.dir"));

    public static void executeOrder66(Path file) {
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

    private static boolean compareFilesByteByByte(Path path, byte[] referenceBytes) {
        try {
            if (Files.size(path) != referenceBytes.length) {
                return false;
            }

            try (InputStream is = new BufferedInputStream(new LockFreeInputStream(path))) {
                int b;
                int i = 0;
                while ((b = is.read()) != -1) {
                    if (b != (referenceBytes[i++] & 0xFF)) { // & 0xFF ensures unsigned comparison
                        return false;
                    }
                }
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Error comparing file byte by byte: {}", path, e);
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

    public static void deleteDummyFiles() {
        if (clientDummyFiles == null || clientDummyFiles.files.isEmpty()) {
            return;
        }

        var iterator = clientDummyFiles.files.iterator();
        while (iterator.hasNext()) {
            try {
                String filePath = iterator.next();
                Path file = Path.of(filePath);
                if (compareFilesByteByByte(file, smallDummyJar)) {
                    CustomFileUtils.executeOrder66(file);
                }
                iterator.remove();
            } catch (Exception e) {
                LOGGER.error("Failed to delete dummy file", e);
            }
        }

        ConfigTools.save(clientDummyFilesFile, clientDummyFiles);
    }

    // our trick for not able to just delete file on specific filesystem (windows...)
    public static void dummyIT(Path file) {
        try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
            fos.write(smallDummyJar);
            fos.flush();

            if (clientDummyFiles == null) {
                throw new IllegalStateException("clientDummyFiles is null");
            }

            clientDummyFiles.files.add(file.toAbsolutePath().normalize().toString());
        } catch (IOException e) {
            LOGGER.error("Failed to create dummy file: {}", file, e);
        }
    }

    public static String getHash(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");

            try (InputStream is = new BufferedInputStream(new LockFreeInputStream(path));
                 DigestInputStream dis = new DigestInputStream(is, digest)) {
                dis.transferTo(OutputStream.nullOutputStream()); // black hole :p
            }

            byte[] hash = digest.digest();
            return HexFormat.of().formatHex(hash);
        } catch (IOException ignored) { // we don't really care about this exception, file may just not exists or be a directory
        } catch (Exception e) {
            LOGGER.error("Failed to get hash for path: {}", path, e);
        }
        return null;
    }

    public static String getCurseforgeMurmurHash(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }

        long length = 0;

        ByteArrayOutputStream filteredStream = new ByteArrayOutputStream();
        try (InputStream is = new BufferedInputStream(new LockFreeInputStream(file))) {
            int b;
            while ((b = is.read()) != -1) {
                // Filter whitespace: Tab (0x9), LF (0xA), CR (0xD), Space (0x20)
                if (b == 0x9 || b == 0xa || b == 0xd || b == 0x20) {
                    continue;
                }

                filteredStream.write(b);
                length++;
            }
        }

        // magic values
        final int m = 0x5bd1e995;
        final int r = 24;
        long k = 0x0L;
        int seed = 1;
        int shift = 0x0;
        char b;
        long h = (seed ^ length);

        byte[] filteredBytes = filteredStream.toByteArray();

        // Second pass: calculate hash using filtered bytes (no file I/O)
        for (byte byteVal : filteredBytes) {
            b = (char) (byteVal & 0xFF);

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
