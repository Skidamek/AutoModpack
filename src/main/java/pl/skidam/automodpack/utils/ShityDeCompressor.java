package pl.skidam.automodpack.utils;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ShityDeCompressor {
    public static int progress;

    public ShityDeCompressor(File zippedInput, File unZippedOut, boolean extractAll, String fileName) {
        try {
            // extract all files from zip
            if (extractAll) {
                // Math to get the number of entries in the zip file. IDK how to make it better //

                ShityDeCompressor.progress = 0;
                ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zippedInput));
                ZipEntry Zip_Entry = zipInputStream.getNextEntry();

                // how many entries in the zip file
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
                    // progress monitor //
                    entryGoing++;
                    float progress = entryGoing / entries * 100;
                    ShityDeCompressor.progress = (int) progress;
                    // ---------------- //

                    String unZippedFile = unZippedOut + File.separator + Zip_Entry2.getName();
                    if (!Zip_Entry2.isDirectory()) {
                        extractFile(zipInputStream2, unZippedFile, 1024);
                    } else {
                        File directory = new File(unZippedFile);
                        directory.mkdirs();
                    }
                    zipInputStream2.closeEntry();
                    Zip_Entry2 = zipInputStream2.getNextEntry();
                }
                zipInputStream2.close();
            }

            // extract only one file from zip
            if (!extractAll) {
                ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zippedInput));
                ZipEntry Zip_Entry = zipInputStream.getNextEntry();

                while (Zip_Entry != null) {
                    String unZippedFile = unZippedOut + File.separator + Zip_Entry.getName();
                    if (!Zip_Entry.isDirectory()) {
                        if (Zip_Entry.getName().equals(fileName)) {
                            extractFile(zipInputStream, unZippedFile, (int) Zip_Entry.getSize());
                        }
                    }
                    zipInputStream.closeEntry();
                    Zip_Entry = zipInputStream.getNextEntry();
                }
                zipInputStream.close();
            }

        } catch (IOException e) {
        }
    }

    private static void extractFile(ZipInputStream zipInputStream, String unZippedFile, int bufferSize) {
        try {
            BufferedOutputStream Buffered_Output_Stream = new BufferedOutputStream(new FileOutputStream(unZippedFile));
            byte[] Bytes = new byte[bufferSize];
            if (bufferSize == 0 || bufferSize == -1) {
                Bytes = new byte[1024];
            }
            int Read_Byte = 0;
            while ((Read_Byte = zipInputStream.read(Bytes)) > 0) {
                Buffered_Output_Stream.write(Bytes, 0, Read_Byte);
            }
            Buffered_Output_Stream.close();
        } catch (IOException e) { // ignore it
        }
    }
}
