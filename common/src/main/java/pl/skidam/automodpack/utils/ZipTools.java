package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipTools { // TODO clean it up a bit
    public static int progress;
    private static int entries;
    private static float entryGoing; // idk why it needs to be float...

    public static void unZip(File fileZip, File destDir) throws IOException {
        unZip(fileZip, destDir, "none");
    }

    public static void unZip(File fileZip, File destDir, String oneFileToUnzipIfAny) throws IOException {
        byte[] buffer = new byte[1024];
        ZipInputStream zis = new ZipInputStream(new FileInputStream(fileZip));
        ZipEntry zipEntry = zis.getNextEntry();
        if (oneFileToUnzipIfAny.equals("none")) {
            progressBarSetup(fileZip);
            while (zipEntry != null) {
                // Progress bar //
                entryGoing++;
                progress = (int) (entryGoing / entries * 100);
                // System.out.println("Extracting " + zipEntry.getName() + (int) entryGoing + "/" + entries + " " + progress + "%");
                // ------------ //
                File newFile = newFile(destDir, zipEntry);
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    // Fix for Windows-created archives
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }

                    // Write file content
                    FileOutputStream fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
            zis.close();

        } else {
            boolean found = false;
            while (zipEntry != null) {
                if (zipEntry.getName().equals(oneFileToUnzipIfAny)) {
                    found = true;
                    File newFile = newFile(destDir, zipEntry);
                    if (zipEntry.isDirectory()) {
                        if (!newFile.isDirectory() && !newFile.mkdirs()) {
                            throw new IOException("Failed to create directory " + newFile);
                        }
                    } else {
                        // Fix for Windows-created archives
                        File parent = newFile.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs()) {
                            throw new IOException("Failed to create directory " + parent);
                        }

                        // Write file content
                        FileOutputStream fos = new FileOutputStream(newFile);
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                        fos.close();
                    }
                    break;
                } else {
                    zipEntry = zis.getNextEntry();
                }
            }
            zis.closeEntry();
            zis.close();

            if (!found) {
                if (!oneFileToUnzipIfAny.equals("fabric.mod.json") && !oneFileToUnzipIfAny.equals("quilt.mod.json") && !oneFileToUnzipIfAny.equals("mods.toml") && !oneFileToUnzipIfAny.equals("trashed-mods.txt")) {
                    AutoModpack.LOGGER.warn("File not found in archive: " + oneFileToUnzipIfAny);
                }
                throw new IOException("File not found in archive: " + oneFileToUnzipIfAny);
            }
        }
    }

    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

    public static void zipFolder(File unZippedInput, File zipOut) throws IOException {

        FileOutputStream fos = new FileOutputStream(zipOut);
        ZipOutputStream zos = new ZipOutputStream(fos);

        // array of files in unzipped folder
        for (File file : Objects.requireNonNull(unZippedInput.listFiles())) {
            zipFile(file, file.getName(), zos);
        }

        zos.close();
        fos.close();
    }

    public static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }
        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }
            File[] children = fileToZip.listFiles();
            assert children != null;
            for (File childFile : children) {
                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
            }
            return;
        }
        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);
        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }

    private static void progressBarSetup(File zip) throws IOException {
        progress = 0;
        entries = 0;
        entryGoing = 0;

        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zip));
        ZipEntry Zip_Entry = zipInputStream.getNextEntry();

        // How many entries in the zip file to after make percentage of it
        while (Zip_Entry != null) {
            entries++;
            Zip_Entry = zipInputStream.getNextEntry();
        }
        zipInputStream.close();
    }
}
