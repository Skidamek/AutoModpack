package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.config.Jsons;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
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

    public static void forceDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }

        if (Files.exists(file)) {
            dummyIT(file);
        }
    }

    // our implementation of Files.copy, thanks to usage of RandomAccessFile we can copy files that are in use
    public static void copyFile(Path source, Path destination) throws IOException {
        if (!Files.exists(destination)) {
            if (!Files.exists(destination.getParent())) {
                Files.createDirectories(destination.getParent());
            }
            Files.createFile(destination);
        }

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
            throw new IOException("Failed to copy file: " + source + " to: " + destination, e);
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
    // modpackFile can not be null
    // modpackPath can be null
    public static String formatPath(final Path modpackFile, final Path modpackPath) {
        final String modpackFileStr = modpackFile.normalize().toString();
        final String modpackFileStrAbs = modpackFile.toAbsolutePath().normalize().toString();

        String modpackPathStrAbs = null;
        if (modpackPath != null) {
            modpackPathStrAbs = modpackPath.toAbsolutePath().normalize().toString();
        }

        final String cwdStrAbs = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();

        String formattedFile = modpackFileStr;

        // Checks if in file parents paths (absolute path) there is modpack directory (absolute path)
        if (modpackPathStrAbs != null && modpackFileStrAbs.contains(modpackPathStrAbs)) {
            formattedFile = modpackFileStrAbs.replace(modpackPathStrAbs, "");
        } else if (modpackFileStrAbs.contains(cwdStrAbs)) {
            formattedFile = modpackFileStrAbs.replace(cwdStrAbs, "");
        } else {
            LOGGER.error("File: " + modpackFileStr + " is not in modpack directory: " + modpackPathStrAbs + " or current working directory: " + cwdStrAbs);
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

    public static Optional<String> getHash(Path file, String algorithm) {

        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }

            if (algorithm.equalsIgnoreCase("murmur")) {
                return getCurseforgeMurmurHash(file);
            }

            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = raf.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            return Optional.of(convertBytesToHex(hashBytes));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Optional.empty();
    }

    private static String convertBytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }


    private static Optional<String> getCurseforgeMurmurHash(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
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

        return Optional.of(String.valueOf(h));
    }


    public static boolean compareFileHashes(Path file1, Path file2, String algorithm) {
        if (!Files.exists(file1) || !Files.exists(file2)) return false;

        String hash1 = getHash(file1, algorithm).orElse(null);
        String hash2 = getHash(file2, algorithm).orElse(null);

        if (hash1 == null || hash2 == null) return false;

        return hash1.equals(hash2);
    }

    public static List<Path> mapAllFiles(Path directory, List<Path> files) throws IOException {
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(directory)) {
            for (Path path : directoryStream) {
                if (Files.isDirectory(path)) {
                    mapAllFiles(path, files);
                } else {
                    files.add(path);
                }
            }
        }

        return files;
    }

    public static List byteArrayToArrayList(byte[] byteArray) {
        ArrayList<Object> byteList = new ArrayList<>();
        for (byte b : byteArray) {
            byteList.add(b);
        }
        return byteList;
    }

    public static boolean isEmptyDirectory(Path parentPath) throws IOException {
        if (!Files.isDirectory(parentPath)) return false;
        List<Path> files = Files.list(parentPath).toList();
        return files.isEmpty();
    }
}
