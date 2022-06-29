package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.client.ui.LoadingScreen;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.Error;
import pl.skidam.automodpack.utils.WebFileSize;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class SelfUpdater {
    File selfBackup = new File("./AutoModpack/AutoModpack-1.18.x.jar");
    boolean preload;

    public SelfUpdater(boolean preload) {

        this.preload = preload;

        // If latest mod is not same as current mod download new mod.
        // Check how big the mod file is

        if (!selfBackup.exists()) {
            try { Files.copy(selfOut.toPath(), selfBackup.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) { } // ignore
        }

        LOGGER.info("Checking if AutoModpack is up-to-date...");

        long currentBackupSize = selfBackup.length();
        long latestSize = Long.parseLong(WebFileSize.webfileSize(selfLink));

        if (currentBackupSize == 0) { // make backup
            try { Files.copy(selfOut.toPath(), selfBackup.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) { } // ignore
            AutoModpackUpdated = "false";
            LOGGER.info("AutoModpack backup has been created");
        }

        if (currentBackupSize == latestSize) {
            LOGGER.info("Didn't found any updates for AutoModpack!");
            if (!preload) {
                AutoModpackToast.add(4);
            }
            AutoModpackUpdated = "false";
            return;
        }

        // Update found
        AutoModpackDownload();
    }

    public void AutoModpackDownload() {
        LOGGER.info("Update found! Updating!");
        if (!preload) {
            AutoModpackToast.add(2);
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new LoadingScreen()));
        }
        // *magic* downloading
        if (Download.Download(selfLink, selfOut)) {
            LOGGER.error("Failed to update myself!");
            if (!preload) {
                AutoModpackToast.add(5);
                new Error();
            }
            AutoModpackUpdated = "false";
            return;
        }

        try { Files.copy(selfOut.toPath(), selfBackup.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) { } // ignore
        LOGGER.info("Successfully self updated!");
        AutoModpackUpdated = "true";

        if (preload) {
            // TODO make this crash better
            throw new RuntimeException("Successfully updated myself! (AutoModpack)");
        }
    }
}
