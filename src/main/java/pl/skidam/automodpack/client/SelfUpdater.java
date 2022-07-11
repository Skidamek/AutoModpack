package pl.skidam.automodpack.client;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.ModrinthAPI.*;

import java.io.File;
import java.io.IOException;

import net.minecraft.client.MinecraftClient;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.client.ui.LoadingScreen;
import pl.skidam.automodpack.utils.*;
import pl.skidam.automodpack.utils.Error;

public class SelfUpdater {
    boolean preload;

    public SelfUpdater(boolean preload) {

        this.preload = preload;

        // If latest mod is not same as current mod download new mod.
        // Check how big the mod file is

        LOGGER.info("Checking if AutoModpack is up-to-date..." + " preload? " + preload);

        String modrinthID = "k68glP2e"; // AutoModpack ID
        new ModrinthAPI(modrinthID);
        modrinthAPIversion = modrinthAPIversion.split("-")[0];

        if (VERSION.equals(modrinthAPIversion)) {
            LOGGER.info("Didn't found any updates for AutoModpack! You are on the latest version: " + VERSION);
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
        LOGGER.info("Update found! Updating to new version: " + modrinthAPIversion);
        if (!preload) {
            AutoModpackToast.add(2);
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new LoadingScreen()));
        }

        // *magic* downloading
        if (Download.Download(modrinthAPIdownloadUrl, selfBackup)) {
            LOGGER.error("Failed to update myself!");
            if (!preload) {
                AutoModpackToast.add(5);
                new Error();
            }
            AutoModpackUpdated = "false";
            return;
        }

        // shutdown hook to make it the most reliable way to update
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Running Shutdown Hook -- AutoModpack selfupdater");
            File selfBackupUnzipped = new File("./AutoModpack/AutoModpack-temp/");
            new UnZipper(selfBackup, selfBackupUnzipped, true, "none");
            try {
                new Zipper(selfBackupUnzipped, selfOut);
            } catch (IOException e) {
            }
            FileUtils.deleteQuietly(selfBackupUnzipped);
            FileUtils.deleteQuietly(selfBackup);
            System.out.println("Finished Shutdown Hook -- AutoModpack selfupdater!");
        }));

        LOGGER.info("Successfully self updated!");
        AutoModpackUpdated = "true";

        if (preload) {
            // TODO make this crash better
            throw new RuntimeException("Successfully updated myself! (AutoModpack)");
        }
    }
}