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

package pl.skidam.automodpack;

import pl.skidam.automodpack.client.ScreenTools;
import pl.skidam.automodpack.loaders.Loader;
import pl.skidam.automodpack.platforms.ModrinthAPI;
import pl.skidam.automodpack.utils.CustomFileUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

import static pl.skidam.automodpack.StaticVariables.*;

public class SelfUpdater {

    public SelfUpdater() {

        if (Loader.isDevelopmentEnvironment()) {
            return;
        }

        if (Loader.getEnvironmentType().equals("SERVER")) {
            if (!serverConfig.selfUpdater) return;
        }

        if (Loader.getEnvironmentType().equals("CLIENT")) {
            if (!clientConfig.selfUpdater) return;
        }

        LOGGER.info("Checking if AutoModpack is up-to-date...");


        ModrinthAPI automodpack = ModrinthAPI.getModInfoFromID("k68glP2e");

        if (automodpack == null || automodpack.fileVersion == null) {
            LOGGER.error("Couldn't get latest version of AutoModpack from Modrinth API. Likely automodpack isn't updated to your version of minecraft yet...");
            return;
        }

        // If latest mod is not same as current mod download new mod.
        // Check how big the mod file is
        if (automodpack.fileVersion.contains("-")) {
            automodpack.fileVersion = automodpack.fileVersion.split("-")[0];
        }

        String LATEST_VERSION = automodpack.fileVersion.replace(".", "");
        String OUR_VERSION = VERSION.replace(".", "");

        try {
            if (Integer.parseInt(OUR_VERSION) > Integer.parseInt(LATEST_VERSION)) {
                LOGGER.info("You are using pre-released or beta version of AutoModpack: " + VERSION + " latest stable version is: " + automodpack.fileVersion);
                return;
            }
        } catch (NumberFormatException e) {
            // ignore

            // Check if version has any other characters than numbers and if latest version is only numbers
            // Automodpack latest version should always have only numbers
            if (OUR_VERSION.chars().anyMatch(ch -> !Character.isDigit(ch)) && LATEST_VERSION.chars().allMatch(Character::isDigit)) {

                OUR_VERSION = OUR_VERSION.replaceAll("[^0-9]", "");
                LATEST_VERSION = LATEST_VERSION.replaceAll("[^0-9]", "");

                if (Integer.parseInt(OUR_VERSION) >= Integer.parseInt(LATEST_VERSION)) {
                    LOGGER.info("You are using pre-released or beta version of AutoModpack: " + VERSION + " latest stable version is: " + automodpack.fileVersion);
                    return;
                }
            } // We don't want to auto update to beta version, but from beta to newer release, yes.
        }


        // We always want to update to latest release version
        if (OUR_VERSION.equals(LATEST_VERSION) || !automodpack.releaseType.equals("release")) {
            LOGGER.info("Didn't find any updates for AutoModpack! You are on the latest version: " + VERSION);
            return;
        }

        LOGGER.info("Update found! Updating to new version: " + automodpack.fileVersion);

        try {
            Download downloadInstance = new Download();
            downloadInstance.download(automodpack.downloadUrl, automodpackUpdateJar); // Download it

            String localHash = CustomFileUtils.getHashWithRetry(automodpackUpdateJar, "SHA-1");

            if (!localHash.equals(automodpack.SHA1Hash)) {
                LOGGER.error("Hashes are not the same! Downloaded file is corrupted!");
                return;
            }

            // We assume that update jar has always different name than current jar
            Files.copy(automodpackUpdateJar.toPath(), automodpackJar.getParentFile().toPath());
            CustomFileUtils.forceDelete(automodpackUpdateJar, true);

        } catch (Exception e) {
            LOGGER.error("Failed to update myself!");
            return;
        }

        // Shutdown hook to make it the most reliable way to update the mod
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Running Shutdown Hook -- AutoModpack selfupdater");

            CustomFileUtils.dummyIT(automodpackJar);

            System.out.println("Finished Shutdown Hook -- AutoModpack selfupdater!");
        }));

        LOGGER.info("Successfully downloaded update, waiting for shutdown");

        new ReLauncher.Restart(null, "Successfully updated AutoModpack - " + automodpack.fileVersion, false);
    }
}