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

import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.ModpackUtils;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.loaders.Loader;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.ModpackContentTools;
import pl.skidam.automodpack.utils.SetupFiles;

import java.nio.file.Paths;

import static pl.skidam.automodpack.GlobalVariables.*;

public class Preload {
    public static void onPreInitialize() {
        long start = System.currentTimeMillis();
        LOGGER.info("Prelaunching AutoModpack...");
        preload = true;

        String workingDirectory = System.getProperty("user.dir");
        if (workingDirectory.contains("com.qcxr.qcxr")) {
            quest = true;
            LOGGER.info("QuestCraft detected!");
            modsPath = Paths.get("./mods/" + MC_VERSION + "/");
        } else {
            quest = false;
            modsPath = Paths.get("./mods/");
        }

        long startTime = System.currentTimeMillis();
        clientConfig = ConfigTools.loadConfig(clientConfigFile, Jsons.ClientConfigFields.class); // load client config
        serverConfig = ConfigTools.loadConfig(serverConfigFile, Jsons.ServerConfigFields.class); // load server config
        LOGGER.info("Loaded config! took " + (System.currentTimeMillis() - startTime) + "ms");

        new SetupFiles();

        if (!quest) {
            new SelfUpdater();
        }

        if (Loader.getEnvironmentType().equals("CLIENT") && !quest) {
            String selectedModpack = clientConfig.selectedModpack;
            if (selectedModpack != null && !selectedModpack.equals("")) {
                selectedModpackDir = ModpackContentTools.getModpackDir(selectedModpack);
                selectedModpackLink = ModpackContentTools.getModpackLink(selectedModpack);
                Jsons.ModpackContentFields serverModpackContent = ModpackUtils.getServerModpackContent(selectedModpackLink);

                if (serverModpackContent != null) {
                    try {
                        CustomFileUtils.deleteEmptyFiles(Paths.get("./"), false, serverModpackContent.list);
                    } catch (Exception e) {
                        LOGGER.error("Failed to delete empty files!", e);
                    }
                }

                new ModpackUpdater(serverModpackContent, selectedModpackLink, selectedModpackDir);
            }
        }

        LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
    }
}
