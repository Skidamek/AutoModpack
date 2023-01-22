package pl.skidam.automodpack.utils;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.AutoModpack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.security.DigestInputStream;
import java.security.MessageDigest;

public class CustomFileUtils {
    public static void forceDelete(File file) {

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

            if (file.exists()) {
                file.deleteOnExit();
                AutoModpack.LOGGER.info("File {} will be deleted on exit", file.getName());
            }
        }
    }

    public static void copyFile(File source, File destination) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(source);
             FileChannel sourceChannel = inputStream.getChannel();
             FileOutputStream outputStream = new FileOutputStream(destination);
             FileChannel destinationChannel = outputStream.getChannel()) {

            destinationChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
        }
    }

    public static String getSHA512(File file) throws Exception {
        DigestInputStream digestInputStream = new DigestInputStream(new FileInputStream(file), MessageDigest.getInstance("SHA-512"));
        byte[] buffer = new byte[8192];
        while (digestInputStream.read(buffer) != -1);
        byte[] digest = digestInputStream.getMessageDigest().digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
