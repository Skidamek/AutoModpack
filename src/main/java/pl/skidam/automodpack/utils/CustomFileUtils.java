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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Everything in this class should force do the thing without throwing any exceptions.
 */

public class CustomFileUtils {
    private static final long maxEmptyZipFolderSize = 168;

    public static void forceDelete(File file, boolean deleteOnExit) {
        if (file.exists()) {
            FileUtils.deleteQuietly(file);

            if (file.exists()) {
                try {
                    FileDeleteStrategy.FORCE.delete(file);
                } catch (IOException ignored) {
                }
            }


            if (file.exists() && file.length() > maxEmptyZipFolderSize) {
                if (file.toString().endsWith(".jar")) {
                    ZipIntoEmptyFolder(file);
                }
            }

            if (file.exists()) {
                try {
                    FileDeleteStrategy.FORCE.delete(file);
                } catch (IOException ignored) {
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

    public static void deleteEmptyFiles(File directory, boolean deleteSubDirsToo, List<Jsons.ModpackContentFields.ModpackContentItems> ignoreList) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (shouldIgnore(file, ignoreList)) {
//                System.out.println("Ignoring: " + file);
                continue;
            }

            if (file.isDirectory()) {

                if (file.getName().startsWith(".")) {
                    continue;
                }

                if (deleteSubDirsToo && isEmptyDirectory(file, ignoreList)) {
//                    System.out.println("Deleting empty dir: " + file);
                    CustomFileUtils.forceDelete(file, false);
                }

                else {
                    deleteEmptyFiles(file, deleteSubDirsToo, ignoreList);
                }

            } else if (file.length() == 0) {
//                System.out.println("Deleting empty file: " + file);
                CustomFileUtils.forceDelete(file, true);
            } else if (file.length() <= maxEmptyZipFolderSize) {
                deleteEmptyZipFolder(file);
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

    public static void ZipIntoEmptyFolder(File zipFile) {
        File folderPath = new File(StaticVariables.automodpackDir + File.separator + "empty");
        folderPath.mkdirs();

        try {
            // Get a reference to the existing ZIP file
            ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(zipFile));

            // Create a new ZIP entry for the empty folder
            ZipEntry zipEntry = new ZipEntry(folderPath + File.separator);
            zipOutputStream.putNextEntry(zipEntry);

            // Close the ZIP output stream
            zipOutputStream.close();

            folderPath.delete();

            System.out.println("Zipped into empty folder: " + zipFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void deleteEmptyZipFolder(File file) {
        if (!file.toString().endsWith(".jar")) {
            return;
        }

        if (JarUtilities.getModIdFromJar(file, true) != null) {
            return;
        }

        CustomFileUtils.forceDelete(file, true);
        System.out.println("Deleted empty zip folder: " + file);
    }

    public static String getHashFromStringOfHashes(String hashes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
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

    public static String getHashWithRetry(File file, String algorithm) throws NoSuchAlgorithmException {
        try {
            return getHash(file, algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw e;
        } catch (Exception e) {
            // ignore NullPointerException
        }

        File tempFile = new File(StaticVariables.automodpackDir + File.separator + file.getName() + ".tmp");
        try {
            CustomFileUtils.copyFile(file, tempFile);
            return getHash(tempFile, algorithm);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("AutoModpack - Cannot copy file for hashing: " + file.getAbsolutePath(), e);
        } finally {
            tempFile.delete();
        }
    }

    public static String getHash(File file, String algorithm) throws Exception {

        if (!file.exists()) return null;

        if (algorithm.equals("murmur")) {
            return getCurseforgeMurmurHash(file.toPath());
        }

        MessageDigest md = MessageDigest.getInstance(algorithm);

        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }

        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }

        return sb.toString();
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

    public static boolean compareFileHashes(File file1, File file2, String algorithm) throws Exception {
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
