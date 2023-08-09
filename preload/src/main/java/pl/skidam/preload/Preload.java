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

package pl.skidam.preload;

import pl.skidam.automodpack.GlobalVariables;
import pl.skidam.automodpack.SelfUpdater;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.ModpackUtils;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.loaders.Loader;
import pl.skidam.automodpack.utils.*;
import settingdust.preloadingtricks.SetupModCallback;

import java.nio.file.Paths;
import java.util.List;

import static pl.skidam.automodpack.GlobalVariables.*;

public class Preload implements SetupModCallback {

    public Preload() {
        long start = System.currentTimeMillis();

        LOGGER.info("Prelaunching AutoModpack...");

        // Initialize global variables
        new GlobalVariables();

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

        if (serverConfig != null && !serverConfig.externalModpackHostLink.isEmpty()) {
            serverConfig.hostIp = serverConfig.externalModpackHostLink;
            serverConfig.hostLocalIp = serverConfig.externalModpackHostLink;
            LOGGER.info("externalModpackHostLink is deprecated, use hostIp and hostLocalIp instead, setting them to {}", serverConfig.externalModpackHostLink);
            serverConfig.externalModpackHostLink = "";
            ConfigTools.saveConfig(serverConfigFile, serverConfig);
        }

        LOGGER.info("Loaded config! took " + (System.currentTimeMillis() - startTime) + "ms");

        new SetupFiles();

        List<Jsons.ModpackContentFields.ModpackContentItem> serverModpackContentList = null;

        if (!quest) {
            String selectedModpack = clientConfig.selectedModpack;
            if (Loader.getEnvironmentType().equals("CLIENT") && selectedModpack != null && !selectedModpack.equals("")) {
                selectedModpackDir = ModpackContentTools.getModpackDir(selectedModpack);
                selectedModpackLink = ModpackContentTools.getModpackLink(selectedModpack);
                Jsons.ModpackContentFields serverModpackContent = ModpackUtils.getServerModpackContent(selectedModpackLink);

                if (serverModpackContent != null) {
                    serverModpackContentList = serverModpackContent.list;
                }

                new SelfUpdater(serverModpackContent);
                new ModpackUpdater(serverModpackContent, selectedModpackLink, selectedModpackDir);
            } else {
                new SelfUpdater(null);
            }
        }

        try {
            CustomFileUtils.deleteEmptyFiles(Paths.get("./"), serverModpackContentList);
        } catch (Exception e) {
            e.printStackTrace();
        }

        LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
    }
}
