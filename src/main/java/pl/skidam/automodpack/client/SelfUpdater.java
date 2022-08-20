package pl.skidam.automodpack.client;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.JarUtilities.selfBackup;
import static pl.skidam.automodpack.utils.JarUtilities.selfOut;
import static pl.skidam.automodpack.utils.ModrinthAPI.*;

import java.io.File;
import java.io.IOException;

import net.minecraft.client.MinecraftClient;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.client.ui.LoadingScreen;
import pl.skidam.automodpack.ui.ScreenBox;
import pl.skidam.automodpack.utils.*;
import pl.skidam.automodpack.utils.Error;

public class SelfUpdater {
    boolean preload;

    public SelfUpdater(boolean preload) {

        this.preload = preload;
        LOGGER.info("Checking if AutoModpack is up-to-date..." + " preload? " + preload);

        if (InternetConnectionCheck.InternetConnectionCheck("https://api.modrinth.com/")) {

            // If latest mod is not same as current mod download new mod.
            // Check how big the mod file is
            String modrinthID = "k68glP2e"; // AutoModpack ID
            new ModrinthAPI(modrinthID);
            ModrinthAPI.modrinthAPIversion = ModrinthAPI.modrinthAPIversion.split("-")[0];

            String modrinthAPIversion = ModrinthAPI.modrinthAPIversion.replace(".", "");
            String VERSION = AutoModpackMain.VERSION.replace(".", "");

            if (Integer.parseInt(VERSION) > Integer.parseInt(modrinthAPIversion)) {
                LOGGER.info("You are using pre-released or beta version of AutoModpack: " + AutoModpackMain.VERSION + " latest stable version is: " + ModrinthAPI.modrinthAPIversion);
                if (!preload) {
                    AutoModpackToast.add(4);
                }
                AutoModpackUpdated = "false";
                return;
            }

            if (VERSION.equals(modrinthAPIversion) || !modrinthAPIversionType.equals("release")) {
                LOGGER.info("Didn't find any updates for AutoModpack! You are on the latest version: " + AutoModpackMain.VERSION);
                if (!preload) {
                    AutoModpackToast.add(4);
                }
                AutoModpackUpdated = "false";
                return;
            }

            // Update found
            AutoModpackDownload();
        }
    }

    public void AutoModpackDownload() {
        LOGGER.info("Update found! Updating to new version: " + ModrinthAPI.modrinthAPIversion);
        if (!preload) {
            new Screen();
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

        // Shutdown hook to make it the most reliable way to update
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Running Shutdown Hook -- AutoModpack selfupdater");
            File selfBackupUnzipped = new File("./AutoModpack/AutoModpack-temp/");
            try {
                new UnZipper(selfBackup, selfBackupUnzipped, "none");
            } catch (IOException e) {
                LOGGER.error("Error unzipping file!");
                e.printStackTrace();
            }
            try {
                new Zipper(selfBackupUnzipped, selfOut);
            } catch (IOException e) {
                LOGGER.error("Error zipping file!");
                e.printStackTrace();
            }
            FileUtils.deleteQuietly(selfBackupUnzipped);
            FileUtils.deleteQuietly(selfBackup);
            System.out.println("Finished Shutdown Hook -- AutoModpack selfupdater!");
        }));

        LOGGER.info("Successfully self updated!");
        AutoModpackUpdated = "true";

        if (preload) {
            new ScreenBox("Successfully updated AutoModpack - " + ModrinthAPI.modrinthAPIversion);
        }
    }
    private static class Screen { // thank you DJtheRedstoner :)
        private Screen() {
            AutoModpackToast.add(2);
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new LoadingScreen()));
        }
    }
}