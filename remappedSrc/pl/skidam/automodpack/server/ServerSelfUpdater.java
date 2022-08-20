package pl.skidam.automodpack.server;

import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.utils.*;

import java.io.File;
import java.io.IOException;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.JarUtilities.*;
import static pl.skidam.automodpack.utils.ModrinthAPI.*;

public class ServerSelfUpdater {

    public ServerSelfUpdater() {

        LOGGER.info("Checking if AutoModpack is up-to-date...");

        if (InternetConnectionCheck.InternetConnectionCheck("https://api.modrinth.com/")) {

            String modrinthID = "k68glP2e"; // AutoModpack ID
            new ModrinthAPI(modrinthID);
            ModrinthAPI.modrinthAPIversion = ModrinthAPI.modrinthAPIversion.split("-")[0];

            String modrinthAPIversion = ModrinthAPI.modrinthAPIversion.replace(".", "");
            String VERSION = AutoModpackMain.VERSION.replace(".", "");

            if (Integer.parseInt(VERSION) > Integer.parseInt(modrinthAPIversion)) {
                LOGGER.info("You are using pre-released or beta version of AutoModpack: " + AutoModpackMain.VERSION + " latest stable version is: " + ModrinthAPI.modrinthAPIversion);
                return;
            }

            if (VERSION.equals(modrinthAPIversion) || !modrinthAPIversionType.equals("release")) {
                LOGGER.info("Didn't find any updates for AutoModpack! You are on the latest version: " + AutoModpackMain.VERSION);
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

            LOGGER.warn("To successfully finish update AutoModpack, please restart server!");
        }
    }
}