package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpackMain;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class UnZipper {
    public static int progress;
    private static int entries;
    private static float entryGoing; // IDK why it needs to be float...

    public UnZipper(File fileZip, File destDir, String oneFileToUnzipIfAny) throws IOException {
        byte[] buffer = new byte[1024];
        ZipInputStream zis = new ZipInputStream(new FileInputStream(fileZip));
        ZipEntry zipEntry = zis.getNextEntry();
        if (oneFileToUnzipIfAny.equals("") || oneFileToUnzipIfAny.equals("none")) {
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
                AutoModpackMain.LOGGER.warn("File not found in archive: " + oneFileToUnzipIfAny);
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
