package pl.skidam.automodpack.server;

import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.ModrinthAPI;
import pl.skidam.automodpack.utils.UnZipper;
import pl.skidam.automodpack.utils.Zipper;

import java.io.File;
import java.io.IOException;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.ModrinthAPI.modrinthAPIdownloadUrl;
import static pl.skidam.automodpack.utils.ModrinthAPI.modrinthAPIversion;

public class ServerSelfUpdater {

    public ServerSelfUpdater() {
        LOGGER.info("Checking if AutoModpack is up-to-date...");

        String modrinthID = "k68glP2e"; // AutoModpack ID
        ModrinthAPI.modrinthAPI(modrinthID);
        modrinthAPIversion = modrinthAPIversion.split("-")[0];

        LOGGER.info("Current AutoModpack version: " + VERSION);
        LOGGER.info("Latest AutoModpack version: " + modrinthAPIversion);

        if (VERSION.equals(modrinthAPIversion)) {
            LOGGER.info("Didn't found any updates for AutoModpack!");
            return;
        }

        LOGGER.info("Update found! Updating!");

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
            }
            FileUtils.deleteQuietly(selfBackupUnzipped);
            FileUtils.deleteQuietly(selfBackup);
            System.out.println("Finished Shutdown Hook -- AutoModpack selfupdater!");
        }));

        LOGGER.info("To successfully update AutoModpack, please restart server!");
    }
}
