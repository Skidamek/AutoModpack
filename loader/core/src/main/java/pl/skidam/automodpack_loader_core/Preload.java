package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core.mods.ModpackLoader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
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
        InetSocketAddress selectedModpackAddress = null;
        InetSocketAddress selectedServerAddress = null;
        if (!clientConfig.selectedModpack.isBlank() && clientConfig.installedModpacks.containsKey(clientConfig.selectedModpack)) {
            var entry = clientConfig.installedModpacks.get(clientConfig.selectedModpack);
            selectedModpackAddress = entry.hostAddress;
            selectedServerAddress = entry.serverAddress;
        }

        // Only selfupdate if no modpack is selected
        if (selectedModpackAddress == null) {
            SelfUpdater.update();
        } else {
            Secrets.Secret secret = SecretsStore.getClientSecret(clientConfig.selectedModpack);

            Jsons.ModpackAddresses modpackAddresses = new Jsons.ModpackAddresses(selectedModpackAddress, selectedServerAddress);
            var optionalLatestModpackContent = ModpackUtils.requestServerModpackContent(modpackAddresses, secret, false);
            var latestModpackContent = ConfigTools.loadModpackContent(selectedModpackDir.resolve(hostModpackContentFile.getFileName()));

            // Use the latest modpack content if available
            if (optionalLatestModpackContent.isPresent()) {
                latestModpackContent = optionalLatestModpackContent.get();

                // Update AutoModpack to server version only if we can get newest modpack content
                if (SelfUpdater.update(latestModpackContent)) {
                    return;
                }
            }

            // Delete dummy files
            CustomFileUtils.deleteDummyFiles(Path.of(System.getProperty("user.dir")), latestModpackContent == null ? null : latestModpackContent.list);

            // Update modpack
            new ModpackUpdater().prepareUpdate(latestModpackContent, modpackAddresses, secret, selectedModpackDir);
        }
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
        THIZ_JAR = FileInspection.getThizJar();
        MODS_DIR = THIZ_JAR.getParent();

        // Get "overrides-automodpack-client.json" zipfile from the AUTOMODPACK_JAR
        try (ZipFile zipFile = new ZipFile(THIZ_JAR.toFile())) {
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

            if (clientConfig.selectedModpack == null) {
                clientConfig.selectedModpack = "";
            }

            // Save changes
            ConfigTools.save(clientConfigFile, clientConfig);
        }

        knownHosts = ConfigTools.load(knownHostsFile, Jsons.KnownHostsFields.class);
        if (knownHosts != null) {
            if (knownHosts.hosts == null) {
                knownHosts.hosts = new HashMap<>();
            }
        }

        try {
            Files.createDirectories(privateDir);
            String os = System.getProperty("os.name").toLowerCase();
            try {
                if (os.contains("win")) {
                    Files.setAttribute(privateDir, "dos:hidden", true);
                } else if (os.contains("nix") || os.contains("nux") || os.contains("aix") || os.contains("mac")) {
                    Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------"); // Corresponds to 0700
                    Files.setPosixFilePermissions(privateDir, perms);
                }
            } catch (UnsupportedOperationException e) {
                LOGGER.warn("Failed to set private directory attributes for os: {}", os, e);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create private directory", e);
        }


        if (serverConfig == null || clientConfig == null) {
            throw new RuntimeException("Failed to load config!");
        }

        LOGGER.info("Loaded config! took {}ms", System.currentTimeMillis() - startTime);
    }
}
