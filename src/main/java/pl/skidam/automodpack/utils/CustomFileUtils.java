/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.utils;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.config.Jsons;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything in this class should force to do the thing without throwing exceptions.
 */

public class CustomFileUtils {
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

    public static void forceDelete(Path file, boolean deleteOnExit) {

        FileUtils.deleteQuietly(file.toFile());

        if (Files.exists(file)) {

            try {
                FileDeleteStrategy.FORCE.delete(file.toFile());
            } catch (IOException ignored) {
            }


            if (Files.exists(file)) {
                if (file.toString().endsWith(".jar")) {
                    dummyIT(file);
                }
            }

            if (deleteOnExit && Files.exists(file)) {
                System.out.println("Deleting on exit: " + file);
                file.toFile().deleteOnExit();
            }
        }
    }

    public static void copyFile(Path source, Path destination) throws IOException {
        if (!Files.exists(source)) {
            throw new FileNotFoundException("Source file does not exist: " + source);
        }

        if (!Files.exists(destination)) {
            if (!Files.exists(destination.getParent())) {
                Files.createDirectories(destination.getParent());
            }
            Files.createFile(destination);
        }

        try (RandomAccessFile sourceFile = new RandomAccessFile(source.toFile(), "r");
             RandomAccessFile destinationFile = new RandomAccessFile(destination.toFile(), "rw")) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = sourceFile.read(buffer)) != -1) {
                destinationFile.write(buffer, 0, bytesRead);
            }
        }
    }

    private static boolean compareFilesByteByByte(Path path, byte[] referenceBytes) {
        try {
            long fileSize = Files.size(path);
            if (fileSize != referenceBytes.length) {
                return false;
            }

            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
                 InputStream referenceInputStream = new ByteArrayInputStream(referenceBytes)) {

                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = raf.read(buffer)) != -1) {
                    for (int i = 0; i < bytesRead; i++) {
                        int fileByte = buffer[i];
                        int referenceByte = referenceInputStream.read();

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


    public static void deleteEmptyFiles(Path directory, boolean deleteSubDirsToo, List<Jsons.ModpackContentFields.ModpackContentItem> ignoreList) {
        try {
            Files.list(directory)
                    .filter(path -> shouldIgnore(path.toFile(), ignoreList))
                    .forEach(path -> {
                        if (Files.isDirectory(path) && !path.getFileName().toString().startsWith(".")) {

                            if (path.toFile().list() == null || path.toFile().list().length == 0) {
                                CustomFileUtils.forceDelete(path, false);

                            } else if (deleteSubDirsToo) {
                                deleteEmptyFiles(path, true, ignoreList);
                            }
                        } else if (compareFilesByteByByte(path, smallDummyJar)) {
                            CustomFileUtils.forceDelete(path, true);
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static boolean shouldIgnore(File file, List<Jsons.ModpackContentFields.ModpackContentItem> ignoreList) {
        if (ignoreList == null) {
            return true;
        }
        return ignoreList.stream().noneMatch(item -> file.getAbsolutePath().replace("\\", "/").endsWith(item.file));
    }

    // dummy IT ez
    public static void dummyIT(Path file) {
        try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
            fos.write(smallDummyJar);
            fos.flush();
        } catch (IOException e) {
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.write(smallDummyJar);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    public static String getHashFromStringOfHashes(String hashes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(hashes.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String getHash(Path file, String algorithm) throws IOException, NoSuchAlgorithmException {

        if (!Files.exists(file)) {
            return null;
        }

        if (algorithm.equals("murmur")) {
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
        return convertBytesToHex(hashBytes);

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


    private static String getCurseforgeMurmurHash(Path file) throws IOException {
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


    public static boolean compareFileHashes(Path file1, Path file2, String algorithm) throws IOException, NoSuchAlgorithmException {
        if (!Files.exists(file1) || !Files.exists(file2)) return false;

        String hash1 = getHash(file1, algorithm);
        String hash2 = getHash(file2, algorithm);

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
}
