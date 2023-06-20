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
import pl.skidam.automodpack.StaticVariables;
import pl.skidam.automodpack.config.Jsons;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Everything in this class should force do the thing without throwing any exceptions.
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

    public static void forceDelete(File file, boolean deleteOnExit) {

        FileUtils.deleteQuietly(file);

        if (file.exists()) {

            try {
                FileDeleteStrategy.FORCE.delete(file);
            } catch (IOException ignored) {
            }


            if (file.exists()) {
                if (file.toString().endsWith(".jar")) {
                    dummyIT(file);
                }
            }

            if (deleteOnExit && file.exists()) {
                System.out.println("Deleting on exit: " + file);
                file.deleteOnExit();
            }
        }
    }

    public static void copyFile(File source, File destination) throws IOException {
        if (!destination.exists()) {
            if (!destination.getParentFile().exists()) {
                destination.getParentFile().mkdirs();
            }
            Files.createFile(destination.toPath());
        }
        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(destination)) {

             FileChannel sourceChannel = inputStream.getChannel();
             FileChannel destinationChannel = outputStream.getChannel();

            destinationChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
        }
    }

    public static void deleteEmptyFiles(File directory, boolean deleteSubDirsToo, List<Jsons.ModpackContentFields.ModpackContentItems> ignoreList) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (shouldIgnore(file, ignoreList)) {
                continue;
            }

            if (file.isDirectory()) {

                if (file.getName().startsWith(".")) {
                    continue;
                }

                if (deleteSubDirsToo && isEmptyDirectory(file, ignoreList)) {
                    CustomFileUtils.forceDelete(file, false);
                }

                else {
                    deleteEmptyFiles(file, deleteSubDirsToo, ignoreList);
                }

            } else if (file.length() < 500) {
                if (Arrays.equals(Files.readAllBytes(file.toPath()), smallDummyJar)) {
                    forceDelete(file, true);
                }
            }
        }
    }

    private static boolean shouldIgnore(File file, List<Jsons.ModpackContentFields.ModpackContentItems> ignoreList) {
        return ignoreList.stream()
                .anyMatch(item -> file.getAbsolutePath().replace("\\", "/").endsWith(item.file));
    }

    private static boolean isEmptyDirectory(File directory, List<Jsons.ModpackContentFields.ModpackContentItems> ignoreList) {
        File[] files = directory.listFiles();

        if (files == null && directory.length() == 0) {
            return true;
        } else {
            for (File file : files) {
                if (!shouldIgnore(file, ignoreList)) {
                    return false;
                }
            }
        }
        
        return false;
    }

    // dummy IT ez
    public static void dummyIT(File file) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(smallDummyJar);
            fos.flush();
        } catch (IOException e) {
            e.printStackTrace();
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

    public static String getHashWithRetry(File file, String algorithm) {
        try {
            return getHash(file, algorithm);
        } catch (Exception e) {
            e.printStackTrace();
        }

        File tempFile = new File(StaticVariables.automodpackDir + File.separator + file.getName() + ".tmp");
        try {
            CustomFileUtils.copyFile(file, tempFile);
            return getHash(tempFile, algorithm);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException("AutoModpack - Cannot copy file for hashing: " + file.getAbsolutePath(), e);
        } finally {
            tempFile.delete();
        }
    }

    public static String getHash(File file, String algorithm) {

        if (!file.exists()) return null;

        try {
            if (algorithm.equals("murmur")) {
                return getCurseforgeMurmurHash(file.toPath());
            }

            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            return convertBytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
            return null;
        }
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

        if (!Files.exists(file)) return null;

        final int m = 0x5bd1e995;
        final int r = 24;
        long k = 0x0L;
        int seed = 1;
        int shift = 0x0;

        // get file size
        long flength = Files.size(file);

        // convert file to byte array
        byte[] byteFile = Files.readAllBytes(file);

        long length = 0;
        char b;
        // get good bytes from file
        for (int i = 0; i < flength; i++) {
            b = (char) byteFile[i];

            if (b == 0x9 || b == 0xa || b == 0xd || b == 0x20) {
                continue;
            }

            length += 1;
        }
        long h = (seed ^ length);

        for (int i = 0; i < flength; i++) {
            b = (char) byteFile[i];

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

    public static boolean compareFileHashes(File file1, File file2, String algorithm) {
        if (!file1.exists() || !file1.exists()) return false;

        String hash1 = getHashWithRetry(file1, algorithm);
        String hash2 = getHashWithRetry(file2, algorithm);

        if (hash1 == null || hash2 == null) return false;

        return hash1.equals(hash2);
    }

    public static List<File> mapAllFiles(File directory, List<File> files) {
        File[] filesInDir = directory.listFiles();
        if (filesInDir == null) {
            return files;
        }

        for (File file : filesInDir) {
            if (file.isDirectory()) {
                mapAllFiles(file, files);
            } else {
                files.add(file);
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
