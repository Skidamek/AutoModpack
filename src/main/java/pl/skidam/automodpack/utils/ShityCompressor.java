package pl.skidam.automodpack.utils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ShityCompressor { // by Skidam so cool i know xD

    public ShityCompressor(File unZippedInput, File zipOut) throws IOException {

        FileOutputStream fos = new FileOutputStream(zipOut);
        ZipOutputStream zos = new ZipOutputStream(fos);
        // TODO clean this mess

        for (File file : Objects.requireNonNull(unZippedInput.listFiles())) {
            if (!file.getName().equals("[CLIENT] mods")) {
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
                                                if (file5.isDirectory()) {
                                                    zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/" + file4.getName() + "/" + file5.getName() + "/"));
                                                    zos.closeEntry();

                                                    for (File file6 : Objects.requireNonNull(file5.listFiles())) {
                                                        if (file6.isFile()) {
                                                            zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/" + file4.getName() + "/" + file5.getName() + "/" + file6.getName()));
                                                            zos.write(FileUtils.readFileToByteArray(file6));
                                                            zos.closeEntry();
                                                        }
                                                        if (file6.isDirectory()) {
                                                            zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/" + file4.getName() + "/" + file5.getName() + "/" + file6.getName() + "/"));
                                                            zos.closeEntry();

                                                            for (File file7 : Objects.requireNonNull(file6.listFiles())) {
                                                                if (file7.isFile()) {
                                                                    zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/" + file4.getName() + "/" + file5.getName() + "/" + file6.getName() + "/" + file7.getName()));
                                                                    zos.write(FileUtils.readFileToByteArray(file7));
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
                        }
                    }
                }
            }
        }

        zos.closeEntry();
        zos.close();
    }
}
