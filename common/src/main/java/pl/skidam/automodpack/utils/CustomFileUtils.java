package pl.skidam.automodpack.utils;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.security.MessageDigest;

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

    public static void deleteEmptyFiles(File directory, boolean deleteSubDirsToo) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                if (deleteSubDirsToo && file.length() == 0) {
                    CustomFileUtils.forceDelete(file, true);
                }
                deleteEmptyFiles(file, deleteSubDirsToo);
            } else if (file.length() == 0) {
                CustomFileUtils.forceDelete(file, true);
            }
        }
    }

    public static String getHash(File file, String algorithm) throws Exception {
        if (!file.exists()) return null;

        MessageDigest md = MessageDigest.getInstance(algorithm);

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
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

    public static boolean compareFileHashes(File file1, File file2, String algorithm) throws Exception {
        if (!file1.exists() || !file1.exists()) return false;

        String hash1 = getHash(file1, algorithm);
        String hash2 = getHash(file2, algorithm);

        if (hash1 == null || hash2 == null) return false;

        return hash1.equals(hash2);
    }
}
