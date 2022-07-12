package pl.skidam.automodpack.utils;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class UnZipper {
    public static int progress;
    public static ZipEntry Zip_Entry;

    public UnZipper(File zippedInput, File unZippedOut, boolean extractAll, String fileName) {
        try {
            // Extract all files from zip
            if (extractAll) {
                // Math to get the number of entries in the zip file. IDK how to make it better //

                UnZipper.progress = 0;
                ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zippedInput));
                ZipEntry Zip_Entry = zipInputStream.getNextEntry();

                // How many entries in the zip file
                int entries = 0;
                while (Zip_Entry != null) {
                    entries++;
                    Zip_Entry = zipInputStream.getNextEntry();
                }
                zipInputStream.close();

                // ---------------------------------------------------------------------------- //

                ZipInputStream zipInputStream2 = new ZipInputStream(new FileInputStream(zippedInput));
                ZipEntry Zip_Entry2 = zipInputStream2.getNextEntry();

                float entryGoing = 0;

                while (Zip_Entry2 != null) {
                    // Progress monitor //
                    entryGoing++;
                    float progress = entryGoing / entries * 100;
                    UnZipper.progress = (int) progress;
                    // ---------------- //

                    String unZippedFile = unZippedOut + File.separator + Zip_Entry2.getName();
                    if (!Zip_Entry2.isDirectory()) {
                        extractFile(zipInputStream2, unZippedFile);
                    } else {
                        File directory = new File(unZippedFile);
                        directory.mkdirs();
                    }
                    zipInputStream2.closeEntry();
                    Zip_Entry2 = zipInputStream2.getNextEntry();
                }
                zipInputStream2.close();
            }

            // Extract only one file from zip
            if (!extractAll) {
                ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zippedInput));
                Zip_Entry = zipInputStream.getNextEntry();

                while (Zip_Entry != null) {
                    String unZippedFile = unZippedOut + File.separator + Zip_Entry.getName();
                    if (!Zip_Entry.isDirectory()) {
                        if (Zip_Entry.getName().equals(fileName)) {
                            extractFile(zipInputStream, unZippedFile);
                        }
                    }
                    zipInputStream.closeEntry();
                    Zip_Entry = zipInputStream.getNextEntry();
                }
                zipInputStream.close();
            }

        } catch (IOException e) {
            LOGGER.error(e.getMessage());
        }
    }

    private static void extractFile(ZipInputStream zipInputStream, String unZippedFile) {
        try {
            BufferedOutputStream Buffered_Output_Stream = new BufferedOutputStream(new FileOutputStream(unZippedFile));
            byte[] Buffer = new byte[1024];
            if (Zip_Entry != null && Zip_Entry.getSize() > 0) {
                Buffer = new byte[(int) Zip_Entry.getSize()];
            }
            int Read_Byte;
            while ((Read_Byte = zipInputStream.read(Buffer)) > 0) {
                Buffered_Output_Stream.write(Buffer, 0, Read_Byte);
            }
            Buffered_Output_Stream.close();
        } catch (IOException e) { // Ignore it
            LOGGER.error(e.getMessage());
        }
    }
}