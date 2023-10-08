/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack_core;

import pl.skidam.automodpack_common.GlobalVariables;
import pl.skidam.automodpack_common.config.Jsons;
import pl.skidam.automodpack_common.utils.CustomFileUtils;
import pl.skidam.automodpack_core.loader.LoaderManager;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_core.mods.SetupMods;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.utils.DownloadManager;
import pl.skidam.automodpack_core.utils.UpdateType;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class SelfUpdater {

    public static Path automodpackJar = new LoaderManager().getModPath(MOD_ID);
    public static String automodpackID = "k68glP2e"; // AutoModpack modrinth id

    public static void update () {
        update(null);
    }

    public static void update(Jsons.ModpackContentFields serverModpackContent) {

        if (new LoaderManager().isDevelopmentEnvironment()) {
            return;
        }

        if (new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.SERVER) {
            if (!serverConfig.selfUpdater) return;
        }

        if (new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.CLIENT) {
            if (!clientConfig.selfUpdater) return;
        }

        if (automodpackJar != null) {
            automodpackJar = automodpackJar.toAbsolutePath().normalize();
        }

        LOGGER.info("Checking if AutoModpack is up-to-date...");

        List<ModrinthAPI> modrinthAPIList = new ArrayList<>();
        boolean gettingServerVersion;

        // Check if server version is available
        if (serverModpackContent != null && serverModpackContent.automodpackVersion != null) {
            modrinthAPIList.add(ModrinthAPI.getModSpecificVersion(automodpackID, serverModpackContent.automodpackVersion, serverModpackContent.mcVersion));
            gettingServerVersion = true;
        } else {
            modrinthAPIList = ModrinthAPI.getModInfosFromID(automodpackID);
            gettingServerVersion = false;
        }

        String errorMessage = "Couldn't get latest version of AutoModpack from Modrinth API. Likely automodpack isn't updated to your version of minecraft yet...";

        if (modrinthAPIList == null) {
            LOGGER.warn(errorMessage);
            return;
        }

        // Modrinth APIs are sorted from latest to oldest
        for (ModrinthAPI automodpack : modrinthAPIList) {

            if (automodpack == null || automodpack.fileVersion == null) {
                errorMessage = "Couldn't get latest version of AutoModpack from Modrinth API. Likely automodpack isn't updated to your version of minecraft yet...";
                continue;
            }

            if (automodpack.fileVersion.contains("-")) {
                automodpack.fileVersion = automodpack.fileVersion.split("-")[0];
            }

            String LATEST_VERSION = automodpack.fileVersion.replace(".", "");
            String OUR_VERSION = AM_VERSION.replace(".", "");
            boolean snapshot = false;

            if (!gettingServerVersion) {
                try {
                    if (Integer.parseInt(OUR_VERSION) > Integer.parseInt(LATEST_VERSION)) {
                        errorMessage = "You are using pre-released or beta version of AutoModpack: " + AM_VERSION + " latest stable version is: " + automodpack.fileVersion;
                        break; // Break, checked version is lower than installed, meaning that higher version doesn't exist.
                    }
                } catch (NumberFormatException e) {
                    // ignore

                    // Check if version has any other characters than numbers and if latest version is only numbers
                    if (OUR_VERSION.chars().anyMatch(ch -> !Character.isDigit(ch))) {

                        snapshot = true;

                        OUR_VERSION = OUR_VERSION.replaceAll("[^0-9]", "");
                        LATEST_VERSION = LATEST_VERSION.replaceAll("[^0-9]", "");

                        if (Integer.parseInt(OUR_VERSION) > Integer.parseInt(LATEST_VERSION)) {
                            errorMessage = "You are using pre-released or beta version of AutoModpack: " + AM_VERSION + " latest stable version is: " + automodpack.fileVersion;
                            break; // Break, checked version is lower than installed, meaning that higher version doesn't exist.
                        }
                    }
                }
            }

            if (!snapshot) {
                // We always want to update to latest release version unless server is already using snapshot version
                if (gettingServerVersion && OUR_VERSION.equals(LATEST_VERSION)) {
                    errorMessage = "Didn't find any updates for AutoModpack! You are on the server version: " + AM_VERSION;
                    break; // Break, server can provide only one version.
                }

                if (!gettingServerVersion && (OUR_VERSION.equals(LATEST_VERSION) || !"release".equals(automodpack.releaseType))) {
                    errorMessage = "Didn't find any updates for AutoModpack! You are on the latest version: " + AM_VERSION;
                    continue;
                }
            }

            // We got correct version
            // We are currently using outdated snapshot or outdated release version
            // If latest is release, always update
            // If latest is beta/alpha (snapshot), update only if we are using beta/alpha (snapshot)
            if (!gettingServerVersion) {
                LOGGER.info("Update found! Updating to latest version: " + automodpack.fileVersion);
            } else {
                LOGGER.info("Update found! Updating to the server version: " + automodpack.fileVersion);
            }

            installModVersion(automodpack);
            return;
        }

        LOGGER.info(errorMessage);
    }

    public static void installModVersion(ModrinthAPI automodpack) {
        Path automodpackUpdateJar = Paths.get(automodpackDir + File.separator + automodpack.fileName);
        Path newAutomodpackJar;

        try {
            DownloadManager downloadManager = new DownloadManager();

            new ScreenManager().download(downloadManager, "AutoModapck " + automodpack.fileVersion);

            // Download it
            downloadManager.download(
                    automodpackUpdateJar,
                    automodpack.SHA1Hash,
                    automodpack.downloadUrl,
                    () -> LOGGER.info("Downloaded update for AutoModpack."),
                    () -> LOGGER.error("Failed to download update for AutoModpack.")
            );

            downloadManager.joinAll();
            downloadManager.cancelAllAndShutdown();

            // We assume that update jar has always different name than current jar
            newAutomodpackJar = Path.of(automodpackJar.getParent() + File.separator + automodpackUpdateJar.getFileName());
            CustomFileUtils.copyFile(automodpackUpdateJar, newAutomodpackJar);
            CustomFileUtils.forceDelete(automodpackUpdateJar);
        } catch (Exception e) {
            LOGGER.error("Failed to update! " + e);
            return;
        }

        if (preload) {
            new SetupMods().removeMod(automodpackJar);
            new SetupMods().addMod(newAutomodpackJar);
            LOGGER.info("Successfully downloaded and installed update!");
        }

        // Shutdown hook to make it the most reliable way to update the mod
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Running Shutdown Hook -- AutoModpack selfupdater");

            CustomFileUtils.dummyIT(automodpackJar);

            System.out.println("Finished Shutdown Hook -- AutoModpack selfupdater!");
        }));

        if (!preload) {
            LOGGER.info("Successfully downloaded update, waiting for shutdown");
            new ReLauncher.Restart("Successfully updated AutoModpack - " + automodpack.fileVersion, UpdateType.AUTOMODPACK);
        }
    }
}