package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.ManifestReader;
import pl.skidam.automodpack_loader_core.mods.ModpackLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Preload {

    public Preload() {
        try {
            long start = System.currentTimeMillis();
            LOGGER.info("Prelaunching AutoModpack...");
            initializeGlobalVariables();
            loadConfigs();
            updateAll();
            LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private void updateAll() {

        var optionalSelectedModpackDir = ModpackContentTools.getModpackDir(clientConfig.selectedModpack);

        if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER || optionalSelectedModpackDir.isEmpty()) {
            SelfUpdater.update();
            return;
        }

        selectedModpackDir = optionalSelectedModpackDir.get();
        String selectedModpackLink = "";
        if (!clientConfig.selectedModpack.isBlank() && clientConfig.installedModpacks.containsKey(clientConfig.selectedModpack)) {
            selectedModpackLink = clientConfig.installedModpacks.get(clientConfig.selectedModpack);
        }

        // Check if the modpack link is missing or blank
        if (selectedModpackLink == null || selectedModpackLink.isBlank()) {
            SelfUpdater.update();
            return;
        }

        var optionalLatestModpackContent = ModpackUtils.requestServerModpackContent(selectedModpackLink);
        var latestModpackContent = ConfigTools.loadModpackContent(selectedModpackDir.resolve(hostModpackContentFile.getFileName()));

        // Use the latest modpack content if available
        if (optionalLatestModpackContent.isPresent()) {
            latestModpackContent = optionalLatestModpackContent.get();
        }

        // Delete dummy files
        CustomFileUtils.deleteDummyFiles(Path.of(System.getProperty("user.dir")), latestModpackContent == null ? null : latestModpackContent.list);

        // Update AutoModpack
        if (SelfUpdater.update(latestModpackContent)) {
            return;
        }

        // Update modpack
        new ModpackUpdater().prepareUpdate(latestModpackContent, selectedModpackLink, selectedModpackDir);
    }


    private void initializeGlobalVariables() {
        // Initialize global variables
        preload = true;
        LOADER_MANAGER = new LoaderManager();
        MODPACK_LOADER = new ModpackLoader();
        MC_VERSION = LOADER_MANAGER.getModVersion("minecraft");
        // Can't get via automodpack version though loader methods since this mod isn't loaded yet... At least on forge...
        AM_VERSION = ManifestReader.getAutoModpackVersion();
        LOADER_VERSION = LOADER_MANAGER.getLoaderVersion();
        LOADER = LOADER_MANAGER.getPlatformType().toString().toLowerCase();
        AUTOMODPACK_JAR = FileInspection.getAutoModpackJar();
        MODS_DIR = AUTOMODPACK_JAR.getParent();

        // Get "overrides-automodpack-client.json" zipfile from the AUTOMODPACK_JAR
        try (ZipFile zipFile = new ZipFile(AUTOMODPACK_JAR.toFile())) {
            ZipEntry entry = zipFile.getEntry(clientConfigFileOverrideResource);
            if (entry != null) {
                clientConfigOverride = new String(zipFile.getInputStream(entry).readAllBytes());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to open the jar file", e);
        }
    }

    private void loadConfigs() {
        long startTime = System.currentTimeMillis();

        // load client config
        if (clientConfigOverride == null) {
            clientConfig = ConfigTools.load(clientConfigFile, Jsons.ClientConfigFields.class);
        } else {
            // TODO: when connecting to the new server which provides modpack different modpack, ask the user if they want, stop using overrides
            LOGGER.warn("You are using unofficial {} mod", MOD_ID);
            LOGGER.warn("Using client config overrides! Editing the {} file will have no effect", clientConfigFile);
            LOGGER.warn("Remove the {} file from inside the jar or remove and download fresh {} mod jar from modrinth/curseforge", clientConfigFileOverrideResource, MOD_ID);
            clientConfig = ConfigTools.load(clientConfigOverride, Jsons.ClientConfigFields.class);
        }

        // load server config
        serverConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFields.class);

        if (serverConfig != null) {
            int previousServerConfigVersion = serverConfig.DO_NOT_CHANGE_IT;
            serverConfig.DO_NOT_CHANGE_IT = new Jsons.ServerConfigFields().DO_NOT_CHANGE_IT;

            if (previousServerConfigVersion != serverConfig.DO_NOT_CHANGE_IT) {
                LOGGER.info("Updated server config version to {}", serverConfig.DO_NOT_CHANGE_IT);
            }

            // Add current loader to the list
            if (serverConfig.acceptedLoaders == null) {
                serverConfig.acceptedLoaders = List.of(LOADER);
            } else if (!serverConfig.acceptedLoaders.contains(LOADER)) {
                serverConfig.acceptedLoaders.add(LOADER);
            }

            // Check modpack name and fix it if needed, because it will be used for naming a folder on client
            if (!serverConfig.modpackName.isEmpty() && FileInspection.isInValidFileName(serverConfig.modpackName)) {
                serverConfig.modpackName = FileInspection.fixFileName(serverConfig.modpackName);
                LOGGER.info("Changed modpack name to {}", serverConfig.modpackName);
            }

            // Save changes
            ConfigTools.save(serverConfigFile, serverConfig);
        }

        if (clientConfig != null) {
            int previousClientConfigVersion = clientConfig.DO_NOT_CHANGE_IT;
            clientConfig.DO_NOT_CHANGE_IT = new Jsons.ClientConfigFields().DO_NOT_CHANGE_IT;

            if (previousClientConfigVersion != clientConfig.DO_NOT_CHANGE_IT) {
                if (clientConfigOverride == null) {
                    LOGGER.info("Updated client config version to {}", clientConfig.DO_NOT_CHANGE_IT);
                } else {
                    LOGGER.error("Client config version is outdated!");
                }
            }

            // Very important to have this map initialized
            if (clientConfig.installedModpacks == null) {
                clientConfig.installedModpacks = new HashMap<>();
            }

            // Save changes
            ConfigTools.save(clientConfigFile, clientConfig);
        }

        LOGGER.info("Loaded config! took " + (System.currentTimeMillis() - startTime) + "ms");
    }
}
