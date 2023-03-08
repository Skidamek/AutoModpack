package pl.skidam.automodpack.utils;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.StaticVariables;
import pl.skidam.automodpack.config.Config;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Everything in this class should force do the thing without throwing any exceptions.
 */

public class CustomFileUtils {
    public static void forceDelete(File file, boolean deleteOnExit) {
        if (file.exists()) {
            FileUtils.deleteQuietly(file);

            if (file.exists()) {
                try {
                    FileDeleteStrategy.FORCE.delete(file);
                } catch (IOException ignored) {
                }
            }

            if (file.exists()) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(new byte[0]);
                } catch (IOException ignored) {
                }
            }

            if (file.exists()) {
                try {
                    FileDeleteStrategy.FORCE.delete(file);
                } catch (IOException ignored) {
                }
            }

            if (deleteOnExit && file.exists()) {
                file.deleteOnExit();
            }
        }
    }

    public static void copyFile(File source, File destination) throws IOException {
        if (!destination.exists()) {
            if (!destination.getParentFile().exists()) destination.getParentFile().mkdirs();
            Files.createFile(destination.toPath());
        }
        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(destination)) {

             FileChannel sourceChannel = inputStream.getChannel();
             FileChannel destinationChannel = outputStream.getChannel();

            destinationChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
        }
    }

    public static void deleteEmptyFiles(File directory, boolean deleteSubDirsToo, List<Config.ModpackContentFields.ModpackContentItems> ignoreList) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (shouldIgnore(file, ignoreList)) {
                System.out.println("Ignoring: " + file);
                continue;
            }

            if (file.isDirectory()) {
                if (deleteSubDirsToo && isEmptyDirectory(file, ignoreList)) {
                    System.out.println("Deleting empty dir: " + file);
                    CustomFileUtils.forceDelete(file, true);
                } else {
                    deleteEmptyFiles(file, deleteSubDirsToo, ignoreList);
                }
            } else if (file.length() == 0) {
                System.out.println("Deleting empty file: " + file);
                CustomFileUtils.forceDelete(file, true);
            }
        }
    }

    private static boolean shouldIgnore(File file, List<Config.ModpackContentFields.ModpackContentItems> ignoreList) {
        return ignoreList.stream()
                .anyMatch(item -> file.getAbsolutePath().replace("\\", "/").endsWith(item.file));
    }

    private static boolean isEmptyDirectory(File directory, List<Config.ModpackContentFields.ModpackContentItems> ignoreList) {
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


    public static String getHash(File file, String algorithm) throws Exception {
        if (!file.exists()) return null;

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
}
