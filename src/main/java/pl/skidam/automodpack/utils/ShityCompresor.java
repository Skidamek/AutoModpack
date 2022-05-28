package pl.skidam.automodpack.utils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ShityCompresor { // by skidam

    public ShityCompresor(File unzipedInput, File zipOut) {

        try {
            FileOutputStream fos = new FileOutputStream(zipOut);
            ZipOutputStream zos = new ZipOutputStream(fos);
            zos.flush();

            // TODO clean this mess

            for (File file : Objects.requireNonNull(unzipedInput.listFiles())) {
                if (file.isFile()) {
                    zos.putNextEntry(new ZipEntry(file.getName()));
                    zos.write(FileUtils.readFileToByteArray(file));
                    zos.closeEntry();
                }
                if (file.isDirectory()) {
                    zos.putNextEntry(new ZipEntry(file.getName() + "/"));
                    zos.closeEntry();
                    for (File file2 : Objects.requireNonNull(file.listFiles())) {
                        if (file2.isFile()) {
                            zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName()));
                            zos.write(FileUtils.readFileToByteArray(file2));
                            zos.closeEntry();
                        }
                        if (file2.isDirectory()) {
                            zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/"));
                            zos.closeEntry();

                            for (File file3 : Objects.requireNonNull(file2.listFiles())) {
                                if (file3.isFile()) {
                                    zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName()));
                                    zos.write(FileUtils.readFileToByteArray(file3));
                                    zos.closeEntry();
                                }
                                if (file3.isDirectory()) {
                                    zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/"));
                                    zos.closeEntry();

                                    for (File file4 : Objects.requireNonNull(file3.listFiles())) {
                                        if (file4.isFile()) {
                                            zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/" + file4.getName()));
                                            zos.write(FileUtils.readFileToByteArray(file4));
                                            zos.closeEntry();
                                        }
                                        if (file4.isDirectory()) {
                                            zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/" + file4.getName() + "/"));
                                            zos.closeEntry();

                                            for (File file5 : Objects.requireNonNull(file4.listFiles())) {
                                                if (file5.isFile()) {
                                                    zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/" + file4.getName() + "/" + file5.getName()));
                                                    zos.write(FileUtils.readFileToByteArray(file5));
                                                    zos.closeEntry();
                                                }
                                                if (file4.isDirectory()) {
                                                    zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/" + file4.getName() + "/" + file5.getName() + "/"));
                                                    zos.closeEntry();

                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            zos.closeEntry();
            zos.close();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
