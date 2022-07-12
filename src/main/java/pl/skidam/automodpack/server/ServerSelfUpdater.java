package pl.skidam.automodpack.server;

import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.ModrinthAPI;
import pl.skidam.automodpack.utils.UnZipper;
import pl.skidam.automodpack.utils.Zipper;

import java.io.File;
import java.io.IOException;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.ModrinthAPI.modrinthAPIdownloadUrl;

public class ServerSelfUpdater {

    public ServerSelfUpdater() {
        LOGGER.info("Checking if AutoModpack is up-to-date...");

        String modrinthID = "k68glP2e"; // AutoModpack ID
        new ModrinthAPI(modrinthID);
        ModrinthAPI.modrinthAPIversion = ModrinthAPI.modrinthAPIversion.split("-")[0];

        String modrinthAPIversion = ModrinthAPI.modrinthAPIversion.replace(".", "");
        String VERSION = AutoModpackMain.VERSION.replace(".", "");

        if (Integer.parseInt(VERSION) > Integer.parseInt(modrinthAPIversion)) {
            LOGGER.info("You are using pre-release version of AutoModpack: " + AutoModpackMain.VERSION + " latest stable version is: " + ModrinthAPI.modrinthAPIversion);
            return;
        }

        if (VERSION.equals(modrinthAPIversion)) {
            LOGGER.info("Didn't found any updates for AutoModpack! You are on the latest version: " + AutoModpackMain.VERSION);
            return;
        }

        LOGGER.info("Update found! Updating to new version: " + ModrinthAPI.modrinthAPIversion);

        // *magic* downloading
        if (Download.Download(modrinthAPIdownloadUrl, selfBackup)) {
            LOGGER.error("Failed to update myself!");
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
                LOGGER.error("Error unzipping file!");
            }
            FileUtils.deleteQuietly(selfBackupUnzipped);
            FileUtils.deleteQuietly(selfBackup);
            System.out.println("Finished Shutdown Hook -- AutoModpack selfupdater!");
        }));

        LOGGER.warn("To successfully update AutoModpack, please restart server!");
    }
}