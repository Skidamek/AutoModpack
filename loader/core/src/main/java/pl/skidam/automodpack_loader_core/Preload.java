package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.ModpackConnectionInfo;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core.mods.ModpackLoader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static pl.skidam.automodpack_core.Constants.*;

public class Preload {

    public Preload() {
        try {
            long start = System.currentTimeMillis();
            LOGGER.info("Prelaunching AutoModpack...");
            initializeConstants();
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
        Jsons.PersistedModpackConnection selectedConnection = null;
        if (!clientConfig.selectedModpack.isBlank() && clientConfig.installedModpacks.containsKey(clientConfig.selectedModpack)) {
            selectedConnection = clientConfig.installedModpacks.get(clientConfig.selectedModpack);
        }
        ModpackConnectionInfo selectedConnectionInfo = ModpackConnectionInfo.fromPersisted(selectedConnection);

        // Only selfupdate if no modpack is selected
        if (selectedConnectionInfo == null || !selectedConnectionInfo.isUsable()) {
            SelfUpdater.update();
            LegacyClientCacheUtils.deleteDummyFiles();
        } else {
            var optionalLatestModpackContent = ModpackUtils.requestServerModpackContent(selectedConnectionInfo, false);
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
            LegacyClientCacheUtils.deleteDummyFiles();

            if (clientConfig.updateSelectedModpackOnLaunch) {
                new ModpackUpdater(latestModpackContent, selectedConnectionInfo, selectedConnection, selectedModpackDir).processModpackUpdate(null);
            }
        }
    }


    private void initializeConstants() {
        // Initialize global variables
        preload = true;
        PRELOAD_TIME = System.currentTimeMillis();
        LOADER_MANAGER = new LoaderManager();
        MODPACK_LOADER = new ModpackLoader();
        MC_VERSION = LOADER_MANAGER.getModVersion("minecraft");
        LOADER_VERSION = LOADER_MANAGER.getLoaderVersion();
        LOADER = LOADER_MANAGER.getPlatformType().toString().toLowerCase();
        THIS_MOD_JAR = JarUtils.getJarPath(this.getClass());
        AM_VERSION = FileInspection.getModVersion(THIS_MOD_JAR);
        MODS_DIR = THIS_MOD_JAR.getParent();

        // Get "overrides-automodpack-client.json" zipfile from the AUTOMODPACK_JAR
        try (ZipInputStream zis = new ZipInputStream(new LockFreeInputStream(THIS_MOD_JAR))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(clientConfigFileOverrideResource)) {
                    clientConfigOverride = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read overrides from jar", e);
        }
    }

    private void loadConfigs() {
        long startTime = System.currentTimeMillis();

        // load client config
        if (clientConfigOverride == null) {
            clientConfig = loadClientConfigV3(clientConfigFile);
        } else {
            // TODO: when connecting to the new server which provides modpack different modpack, ask the user if they want, stop using overrides
            LOGGER.warn("You are using unofficial {} mod", MOD_ID);
            LOGGER.warn("Using client config overrides! Editing the {} file will have no effect", clientConfigFile);
            LOGGER.warn("Remove the {} file from inside the jar or remove and download fresh {} mod jar from modrinth/curseforge", clientConfigFileOverrideResource, MOD_ID);
            clientConfig = loadClientConfigV3(clientConfigOverride);
        }

        var serverConfigVersion = ConfigTools.softLoad(serverConfigFile, Jsons.VersionConfigField.class);
        if (serverConfigVersion != null) {
            if (serverConfigVersion.DO_NOT_CHANGE_IT == 1) {
                // Update the configs schemes to make this update not as breaking as it could be
                var serverConfigV1 = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFieldsV1.class);
                var serverConfigV2 = ConfigTools.softLoad(serverConfigFile, Jsons.ServerConfigFieldsV2.class);
                if (serverConfigV1 != null && serverConfigV2 != null) {
                    serverConfigVersion.DO_NOT_CHANGE_IT = 2;
                    serverConfigV2.DO_NOT_CHANGE_IT = 2;

                    if (serverConfigV1.hostIp.isBlank()) {
                        serverConfigV2.addressToSend = "";
                    } else {
                        serverConfigV2.addressToSend = AddressHelpers.parse(serverConfigV1.hostIp).getHostString();
                    }

                    if (serverConfigV1.hostModpackOnMinecraftPort) {
                        serverConfigV2.bindPort = -1;
                        serverConfigV2.portToSend = -1;
                    } else {
                        serverConfigV2.bindPort = serverConfigV1.hostPort;
                        serverConfigV2.portToSend = serverConfigV1.hostPort;
                    }
                }

                ConfigTools.save(serverConfigFile, serverConfigV2);
                LOGGER.info("Updated server config version to {}", serverConfigVersion.DO_NOT_CHANGE_IT);
            }
        }

        // load server config
        serverConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFieldsV2.class);

        if (serverConfig != null) {
            // Add current loader to the list
            if (serverConfig.acceptedLoaders == null) {
                serverConfig.acceptedLoaders = Set.of(LOADER);
            } else {
                serverConfig.acceptedLoaders.add(LOADER);
            }

            // Check modpack name and fix it if needed, because it will be used for naming a folder on client
            if (!serverConfig.modpackName.isEmpty() && FileInspection.isInValidFileName(serverConfig.modpackName)) {
                serverConfig.modpackName = FileInspection.fixFileName(serverConfig.modpackName);
                LOGGER.info("Changed modpack name to {}", serverConfig.modpackName);
            }

            ConfigUtils.normalizeServerConfig(serverConfig);

            // Save changes
            ConfigTools.save(serverConfigFile, serverConfig);
        }

        if (clientConfig != null) {
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

        if (Files.isRegularFile(knownHostsFile)) {
            try {
                knownHosts = Jsons.loadKnownHostsV2(Files.readString(knownHostsFile));
            } catch (IOException e) {
                LOGGER.error("Failed to read known hosts, regenerating defaults", e);
                knownHosts = new Jsons.KnownHostsFieldsV2();
            }
        } else {
            knownHosts = new Jsons.KnownHostsFieldsV2();
        }
        knownHosts.normalize();
        ConfigTools.save(knownHostsFile, knownHosts);

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
            } catch (UnsupportedOperationException | IOException e) {
                LOGGER.debug("Failed to set private directory attributes for os: {}", os);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create private directory", e);
        }


        if (serverConfig == null || clientConfig == null) {
            throw new RuntimeException("Failed to load config!");
        }

        LOGGER.info("Loaded config! took {}ms", System.currentTimeMillis() - startTime);
    }

    private static Jsons.ClientConfigFieldsV3 loadClientConfigV3(Path configFile) {
        Jsons.VersionConfigField version = ConfigTools.softLoad(configFile, Jsons.VersionConfigField.class);
        if (version == null || version.DO_NOT_CHANGE_IT >= 3) {
            return ConfigTools.load(configFile, Jsons.ClientConfigFieldsV3.class);
        }

        try {
            Jsons.ClientConfigFieldsV3 migrated = migrateClientConfig(version.DO_NOT_CHANGE_IT, Files.readString(configFile));
            if (migrated != null) {
                ConfigTools.save(configFile, migrated);
                LOGGER.info("Updated client config version to {}", migrated.DO_NOT_CHANGE_IT);
                return migrated;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read client config for migration", e);
        }

        return ConfigTools.load(configFile, Jsons.ClientConfigFieldsV3.class);
    }

    private static Jsons.ClientConfigFieldsV3 loadClientConfigV3(String json) {
        Jsons.VersionConfigField version = ConfigTools.load(json, Jsons.VersionConfigField.class);
        if (version == null || version.DO_NOT_CHANGE_IT >= 3) {
            return ConfigTools.load(json, Jsons.ClientConfigFieldsV3.class);
        }

        Jsons.ClientConfigFieldsV3 migrated = migrateClientConfig(version.DO_NOT_CHANGE_IT, json);
        return migrated == null ? ConfigTools.load(json, Jsons.ClientConfigFieldsV3.class) : migrated;
    }

    private static Jsons.ClientConfigFieldsV3 migrateClientConfig(int version, String json) {
        if (version <= 1) {
            Jsons.ClientConfigFieldsV1 legacy = ConfigTools.load(json, Jsons.ClientConfigFieldsV1.class);
            if (legacy == null) {
                return null;
            }
            Jsons.ClientConfigFieldsV3 migrated = new Jsons.ClientConfigFieldsV3();
            migrated.selectedModpack = legacy.selectedModpack == null ? "" : legacy.selectedModpack;
            migrated.selfUpdater = legacy.selfUpdater;
            migrated.installedModpacks = new HashMap<>();
            return migrated;
        }

        Jsons.ClientConfigFieldsV2 legacy = ConfigTools.load(json, Jsons.ClientConfigFieldsV2.class);
        if (legacy == null) {
            return null;
        }

        Jsons.ClientConfigFieldsV3 migrated = new Jsons.ClientConfigFieldsV3();
        migrated.selectedModpack = legacy.selectedModpack == null ? "" : legacy.selectedModpack;
        migrated.updateSelectedModpackOnLaunch = legacy.updateSelectedModpackOnLaunch;
        migrated.selfUpdater = legacy.selfUpdater;
        migrated.syncAutoModpackVersion = legacy.syncAutoModpackVersion;
        migrated.syncLoaderVersion = legacy.syncLoaderVersion;
        migrated.playMusic = legacy.playMusic;
        migrated.allowRemoteNonModpackDeletions = legacy.allowRemoteNonModpackDeletions;
        migrated.installedModpacks = new HashMap<>();
        if (legacy.installedModpacks != null) {
            legacy.installedModpacks.forEach((modpackName, addresses) ->
                migrated.installedModpacks.put(modpackName, Jsons.legacyToPersistedModpackConnection(addresses))
            );
        }
        return migrated;
    }
}
