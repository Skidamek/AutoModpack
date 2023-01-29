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
import java.nio.file.Paths;
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
                AutoModpack.LOGGER.info("File {} will be deleted on exit", file.getName());
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

    public static String getSHA512(File file) throws Exception {
        byte[] fileData = Files.readAllBytes(Paths.get(file.getAbsolutePath()));
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-512");
        byte[] checksum = messageDigest.digest(fileData);
        StringBuilder result = new StringBuilder();
        for (byte b : checksum) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
