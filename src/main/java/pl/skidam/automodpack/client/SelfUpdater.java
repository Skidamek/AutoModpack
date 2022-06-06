package pl.skidam.automodpack.client;

import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.ToastExecutor;
import pl.skidam.automodpack.utils.WebFileSize;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class SelfUpdater {
    File selfBackup = new File("./AutoModpack/AutoModpack.jar");
    File oldAutoModpack = new File("./AutoModpack/OldAutoModpack/AutoModpack.jar");

    public SelfUpdater() {

        // If latest mod is not same as current mod download new mod.
        // Check how big the mod file is

        if (!selfBackup.exists()) {
            try {
                Files.copy(selfOut.toPath(), selfBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        LOGGER.info("Checking if AutoModpack is up-to-date...");

        long currentSize = selfBackup.length();
        long latestSize = Long.parseLong(WebFileSize.webfileSize(selfLink));

        if (currentSize == latestSize) {
            LOGGER.info("Didn't found any updates for AutoModpack!");
            new ToastExecutor(4);
            AutoModpackUpdated = "false";
            return;
        }
        // Update found

        // Backup old AutoModpack
        try {
            Files.copy(selfOut.toPath(), oldAutoModpack.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Download update
        AutoModpackDownload();
    }

    public void AutoModpackDownload() {
        LOGGER.info("Update found! Updating!");
        new ToastExecutor(2);
        // *magic* downloading
        if (Download.Download(selfLink, selfOut)) {
            LOGGER.error("Failed to update myself!");
            new ToastExecutor(5);
            AutoModpackUpdated = "false";
            return;
        }

        try { Files.copy(selfOut.toPath(), selfBackup.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) {throw new RuntimeException(e);}
        LOGGER.info("Successfully self updated!");
        AutoModpackUpdated = "true";
    }
}
