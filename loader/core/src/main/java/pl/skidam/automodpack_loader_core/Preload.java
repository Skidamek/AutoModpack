package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_core.utils.ManifestReader;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Preload {

    public Preload() {
        try {
            long start = System.currentTimeMillis();
            LOGGER.info("Prelaunching AutoModpack...");
            initializeGlobalVariables();
            loadConfigs();
            createPaths();
            updateAll();
            LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private void updateAll() {

        var optionalSelectedModpackDir = ModpackContentTools.getModpackDir(clientConfig.selectedModpack);

        if (LOADER_MANAGER.getEnvironmentType() == LoaderService.EnvironmentType.SERVER || optionalSelectedModpackDir.isEmpty()) {
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
        new ModpackUpdater().startModpackUpdate(latestModpackContent, selectedModpackLink, selectedModpackDir);
    }


    private void initializeGlobalVariables() {
        // Initialize global variables
        preload = true;
        LOADER_MANAGER = new LoaderManager();
        MC_VERSION = LOADER_MANAGER.getModVersion("minecraft");
        // Can't get via automodpack version though loader methods since this mod isn't loaded yet... At least on forge...
        AM_VERSION = ManifestReader.getAutoModpackVersion();
        LOADER_VERSION = LOADER_MANAGER.getLoaderVersion();
        LOADER = LOADER_MANAGER.getPlatformType().toString().toLowerCase();
        AUTOMODPACK_JAR = FileInspection.getAutoModpackJar();
        MODS_DIR = AUTOMODPACK_JAR.getParent();
    }

    private void loadConfigs() {
        long startTime = System.currentTimeMillis();
        clientConfig = ConfigTools.load(clientConfigFile, Jsons.ClientConfigFields.class); // load client config
        serverConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFields.class); // load server config

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
                LOGGER.info("Updated client config version to {}", clientConfig.DO_NOT_CHANGE_IT);
            }

            // Save changes
            ConfigTools.save(clientConfigFile, clientConfig);
        }

        LOGGER.info("Loaded config! took " + (System.currentTimeMillis() - startTime) + "ms");
    }

    private void createPaths() throws IOException {
        Path AMDir = Paths.get("./automodpack/");
        // Check if AutoModpack path exists
        if (!Files.exists(AMDir)) {
            Files.createDirectories(AMDir);
        }

        if (new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.CLIENT) {
            Path modpacks = Paths.get("./automodpack/modpacks/");
            if (!Files.exists(modpacks)) {
                Files.createDirectories(modpacks);
            }
        }
    }
}
