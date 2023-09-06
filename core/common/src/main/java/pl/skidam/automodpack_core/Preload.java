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

import pl.skidam.automodpack_common.config.ConfigTools;
import pl.skidam.automodpack_common.config.Jsons;
import pl.skidam.automodpack_common.utils.CustomFileUtils;
import pl.skidam.automodpack_common.utils.ModpackContentTools;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.ModpackUtils;
import pl.skidam.automodpack_core.loader.LoaderManager;
import pl.skidam.automodpack_core.loader.LoaderService;
import settingdust.preloadingtricks.SetupModCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class Preload implements SetupModCallback {

    public Preload() throws IOException {
        long start = System.currentTimeMillis();

        LOGGER.info("Prelaunching AutoModpack...");

        initializeGlobalVariables();

        loadConfigs();

        createPaths();

        List<Jsons.ModpackContentFields.ModpackContentItem> serverModpackContentList = null;

        if (!quest) {
            String selectedModpack = clientConfig.selectedModpack;

            if (new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.CLIENT && selectedModpack != null && !selectedModpack.isEmpty()) {
                selectedModpackDir = ModpackContentTools.getModpackDir(selectedModpack);
                selectedModpackLink = ModpackContentTools.getModpackLink(selectedModpack);
                Jsons.ModpackContentFields serverModpackContent = ModpackUtils.getServerModpackContent(selectedModpackLink);

                if (serverModpackContent != null) {
                    serverModpackContentList = serverModpackContent.list;
                }

                SelfUpdater.update(serverModpackContent);
                new ModpackUpdater().startModpackUpdate(serverModpackContent, selectedModpackLink, selectedModpackDir);
            } else {
                SelfUpdater.update();
            }
        }

        try {
            CustomFileUtils.deleteEmptyFiles(Paths.get("./"), serverModpackContentList);
        } catch (Exception e) {
            e.printStackTrace();
        }

        LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
    }

    private void initializeGlobalVariables() {
        // Initialize global variables
        preload = true;
        MC_VERSION = new LoaderManager().getModVersion("minecraft");
        AM_VERSION = new LoaderManager().getModVersion("automodpack");
        LOADER_VERSION = new LoaderManager().getLoaderVersion();
        LOADER = new LoaderManager().getPlatformType().toString().toLowerCase();

        String workingDirectory = System.getProperty("user.dir");
        if (workingDirectory.contains("com.qcxr.qcxr")) {
            quest = true;
            LOGGER.info("QuestCraft detected!");
            modsPath = Paths.get("./mods/" + MC_VERSION + "/");
        } else {
            quest = false;
            modsPath = Paths.get("./mods/");
        }
    }

    private void loadConfigs() {
        long startTime = System.currentTimeMillis();
        clientConfig = ConfigTools.loadConfig(clientConfigFile, Jsons.ClientConfigFields.class); // load client config
        serverConfig = ConfigTools.loadConfig(serverConfigFile, Jsons.ServerConfigFields.class); // load server config

        // add current loader to the list
        if (serverConfig != null) {
            if (serverConfig.acceptedLoaders == null) {
                serverConfig.acceptedLoaders = List.of(LOADER);
            } else if (!serverConfig.acceptedLoaders.contains(LOADER)) {
                serverConfig.acceptedLoaders.add(LOADER);
            }
        }

        if (serverConfig != null && !serverConfig.externalModpackHostLink.isEmpty()) {
            serverConfig.hostIp = serverConfig.externalModpackHostLink;
            serverConfig.hostLocalIp = serverConfig.externalModpackHostLink;
            LOGGER.info("externalModpackHostLink is deprecated, use hostIp and hostLocalIp instead, setting them to {}", serverConfig.externalModpackHostLink);
            serverConfig.externalModpackHostLink = "";
            ConfigTools.saveConfig(serverConfigFile, serverConfig);
        }

        LOGGER.info("Loaded config! took " + (System.currentTimeMillis() - startTime) + "ms");
    }

    private void createPaths() throws IOException {
        Path AMdir = Paths.get("./automodpack/");
        // Check if AutoModpack path exists
        if (!Files.exists(AMdir)) {
            Files.createDirectories(AMdir);
        }

        if (new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.CLIENT) {
            Path modpacks = Paths.get("./automodpack/modpacks/");
            if (!Files.exists(modpacks)) {
                Files.createDirectories(modpacks);
            }
        }
    }
}
