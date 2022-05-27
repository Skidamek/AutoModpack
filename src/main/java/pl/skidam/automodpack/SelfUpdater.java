package pl.skidam.automodpack;

import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.Error;
import pl.skidam.automodpack.utils.ToastExecutor;
import pl.skidam.automodpack.utils.WebFileSize;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static pl.skidam.automodpack.AutoModpackClient.*;

public class SelfUpdater {

    File selfBackup = new File("./AutoModpack/AutoModpack.jar");
    File oldAutoModpack = new File("./AutoModpack/OldAutoModpack/AutoModpack.jar");
    boolean LatestVersion = false;

    public SelfUpdater() {

        // if latest mod is not same as current mod download new mod.
        // Check how big the mod file is
        if (!selfBackup.exists()) { new ToastExecutor(2); }
        if (selfBackup.exists()) {
            AutoModpackClient.LOGGER.info("Checking if AutoModpack is up-to-date...");

            long currentSize = selfBackup.length();
            long latestSize;
            try {
                latestSize = Long.parseLong(WebFileSize.webfileSize(selfLink));
            } catch (Exception e) {
                AutoModpackClient.AutoModpackUpdated = "false";
                AutoModpackClient.LOGGER.error("Make sure that you have an internet connection!");
                new Error();
                return;
            }

            if (currentSize != latestSize) {
                AutoModpackClient.LOGGER.info("Update found! Updating!");
                new ToastExecutor(2);

            } else {
                AutoModpackClient.LOGGER.info("Didn't found any updates for AutoModpack!");
                new ToastExecutor(4);
                LatestVersion = true;
                AutoModpackClient.AutoModpackUpdated = "false";
            }
        }

        if (!LatestVersion || !selfBackup.exists()) {

            File oldAM = new File("./AutoModpack/OldAutoModpack/");
            if (!oldAM.exists()) {
                oldAM.mkdir();
            }
            File selfOutFile = new File("./mods/AutoModpack.jar");
            if (selfOutFile.exists()) {
                try { Files.copy(selfOut.toPath(), oldAutoModpack.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) {throw new RuntimeException(e);}
            } else {
                AutoModpackClient.LOGGER.error("LoL how did you get here? You should have the AutoModpack.jar in your mods folder.");
            }

            // *magic* downloading

            if (!Download.Download(selfLink, selfOut)) {
                AutoModpackClient.LOGGER.error("Failed to update myself!");
                new ToastExecutor(5);
                AutoModpackClient.AutoModpackUpdated = "false";
                return;
            }

            try { Files.copy(selfOut.toPath(), selfBackup.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) {throw new RuntimeException(e);}
            AutoModpackClient.LOGGER.info("Successfully self updated!");
            AutoModpackClient.AutoModpackUpdated = "true";

        } else {
            AutoModpackClient.AutoModpackUpdated = "false";
        }
    }
}
