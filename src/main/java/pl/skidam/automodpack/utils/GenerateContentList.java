package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.server.HostModpack;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class GenerateContentList {
    public static List<String> generateContentList(File zipToMakeContentListFrom) throws IOException {
        List<String> folderContent = new ArrayList<>();
        if (!HostModpack.MODPACK_CONTENT_FILE.toFile().exists()) {
            if (!HostModpack.MODPACK_CONTENT_FILE.toFile().createNewFile()) {
                AutoModpackMain.LOGGER.error("Content file can not been created");
            }
        }
        ZipFile zipFileSize = new ZipFile(zipToMakeContentListFrom);
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipToMakeContentListFrom));
        ZipEntry zipEntry = zis.getNextEntry();
        while (zipEntry != null) {
            ZipEntry zipEntrySize = zipFileSize.getEntry(String.valueOf(zipEntry));
            folderContent.add(zipEntry + " |=<|+|>=| " + zipEntrySize.getSize());
            zipEntry = zis.getNextEntry();
        }
        zis.close();
        zipFileSize.close();
        return folderContent;
    }
}