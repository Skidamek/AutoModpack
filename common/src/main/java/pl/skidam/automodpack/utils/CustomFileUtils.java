package pl.skidam.automodpack.utils;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.AutoModpack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.security.MessageDigest;

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

            if (file.exists()) { // if mod to delete still exists
                try {
                    java.io.File emptyFolder = new File(AutoModpack.automodpackDir + File.separator + "empty");
                    if (!emptyFolder.exists()) {
                        emptyFolder.mkdirs();
                    }
                    ZipTools.zipFolder(emptyFolder, file);
                    FileDeleteStrategy.FORCE.delete(emptyFolder);
                    FileDeleteStrategy.FORCE.delete(file);
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
             FileChannel sourceChannel = inputStream.getChannel();
             FileOutputStream outputStream = new FileOutputStream(destination);
             FileChannel destinationChannel = outputStream.getChannel()) {

            destinationChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
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

    public static boolean compareHashWithFile(File file, String hash, String algorithm) throws Exception {
        String fileHash = getHash(file, algorithm);

        if (fileHash == null) return false;

        if (!fileHash.equals(hash)) {
            CustomFileUtils.forceDelete(file, false);
            return false;
        } else {
            return true;
        }
    }
}
