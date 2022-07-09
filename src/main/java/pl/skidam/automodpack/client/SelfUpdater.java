package pl.skidam.automodpack.client;

import static pl.skidam.automodpack.AutoModpackMain.AutoModpackUpdated;
import static pl.skidam.automodpack.AutoModpackMain.LOGGER;
import static pl.skidam.automodpack.AutoModpackMain.selfBackup;
import static pl.skidam.automodpack.AutoModpackMain.selfOut;
import static pl.skidam.automodpack.utils.modrinthAPI.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.client.ui.LoadingScreen;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.Error;
import pl.skidam.automodpack.utils.ShityCompressor;
import pl.skidam.automodpack.utils.ShityDeCompressor;
import pl.skidam.automodpack.utils.modrinthAPI;

public class SelfUpdater {
    boolean preload;

    public SelfUpdater(boolean preload) {

        this.preload = preload;

        // If latest mod is not same as current mod download new mod.
        // Check how big the mod file is

        if (!selfBackup.exists()) {
            try { Files.copy(selfOut.toPath(), selfBackup.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) { } // ignore
            LOGGER.info("AutoModpack backup has been created");
        }

        LOGGER.info("Checking if AutoModpack is up-to-date...");

        long currentBackupSize = selfBackup.length();
        String modrinthID = "k68glP2e"; // AutoModpack ID
        modrinthAPI.modrinthAPI(modrinthID);

        if (currentBackupSize == modrinthAPIsize) {
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
        if (Download.Download(modrinthAPIdownloadUrl, selfBackup)) {
            LOGGER.error("Failed to update myself!");
            if (!preload) {
                AutoModpackToast.add(5);
                new Error();
            }
            AutoModpackUpdated = "false";
            return;
        }

        System.out.println("Running Shutdown Hook -- AutoModpack selfupdater");
        File selfBackupUnzipped = new File("./AutoModpack/AutoModpack-temp/");
        new ShityDeCompressor(selfBackup, selfBackupUnzipped, true, "none");
        try {
            new ShityCompressor(selfBackupUnzipped, selfOut, true);
        } catch (IOException e) {
        }
        selfBackupUnzipped.delete();
        System.out.println("Finished Shutdown Hook -- AutoModpack selfupdater!");

        // shutdown hook to make it the most reliable way to update
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("Running Shutdown Hook -- AutoModpack selfupdater");
                File selfBackupUnzipped = new File("./AutoModpack/AutoModpack-temp/");
                new ShityDeCompressor(selfBackup, selfBackupUnzipped, true, "none");
                try {
                    new ShityCompressor(selfBackupUnzipped, selfOut, true);
                } catch (IOException e) {
                }
                selfBackupUnzipped.delete();
                System.out.println("Finished Shutdown Hook -- AutoModpack selfupdater!");
            }
        });

        LOGGER.info("Successfully self updated!");
        AutoModpackUpdated = "true";

        if (preload) {
            // TODO make this crash better
            System.exit(0); // idk if it really needed
            throw new RuntimeException("Successfully updated myself! (AutoModpack)");
        }

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            LOGGER.warn("Restart your server to properly update AutoModpack!");
        }
    }
}