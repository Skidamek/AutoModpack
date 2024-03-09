package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.loader.LoaderService;
import pl.skidam.automodpack_loader_core.utils.ManifestReader;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Optional;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.GlobalVariables.selectedModpackLink;

public class Preload {

    public Preload() {
        try {
            long start = System.currentTimeMillis();

            LOGGER.info("Prelaunching AutoModpack...");

            initializeGlobalVariables();

            loadConfigs();

            createPaths();

            updateAll(clientConfig.selectedModpack);

            LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private void updateAll(String selectedModpack) {
        var optionalSelectedModpackDir = ModpackContentTools.getModpackDir(selectedModpack);
        if (new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.CLIENT && optionalSelectedModpackDir.isPresent()) {

            selectedModpackDir = optionalSelectedModpackDir.get();

            Optional<String> optionalSelectedModpackLink = ModpackContentTools.getModpackLink(selectedModpackDir);
            if (optionalSelectedModpackLink.isEmpty()) {
                SelfUpdater.update();
                return;
            }

            selectedModpackLink = optionalSelectedModpackLink.get();
            var optionalLatestModpackContent = ModpackUtils.requestServerModpackContent(selectedModpackLink);
            var latestModpackContent = ConfigTools.loadConfig(selectedModpackDir.resolve(hostModpackContentFile.getFileName()), Jsons.ModpackContentFields.class);
            if (optionalLatestModpackContent.isPresent()) {
                latestModpackContent = optionalLatestModpackContent.get();
            }

            if (latestModpackContent == null) {
                SelfUpdater.update();
                return;
            }

            CustomFileUtils.deleteDummyFiles(Paths.get(System.getProperty("user.dir")), latestModpackContent.list);
            SelfUpdater.update(latestModpackContent);
            new ModpackUpdater().startModpackUpdate(latestModpackContent, selectedModpackLink, selectedModpackDir);
        } else {
            SelfUpdater.update();
        }
    }

    private void initializeGlobalVariables() {
        // Initialize global variables
        preload = true;
        MC_VERSION = new LoaderManager().getModVersion("minecraft");
        // Can't get via automodpack version though loader methods since this mod isn't loaded yet... At least on forge...
        AM_VERSION = ManifestReader.getAutoModpackVersion();
        LOADER_VERSION = new LoaderManager().getLoaderVersion();
        LOADER = new LoaderManager().getPlatformType().toString().toLowerCase();
    }

    private void loadConfigs() {
        long startTime = System.currentTimeMillis();
        clientConfig = ConfigTools.loadConfig(clientConfigFile, Jsons.ClientConfigFields.class); // load client config
        serverConfig = ConfigTools.loadConfig(serverConfigFile, Jsons.ServerConfigFields.class); // load server config

        if (serverConfig != null) {

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
            ConfigTools.saveConfig(serverConfigFile, serverConfig);
        }

        if (clientConfig != null) {

            // Make sure to have modpack in installed modpacks
            ModpackUtils.addModpackToList(clientConfig.selectedModpack);

            // Save changes
            ConfigTools.saveConfig(clientConfigFile, clientConfig);

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
