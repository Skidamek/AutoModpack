package pl.skidam.automodpack_loader_core;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_loader_core.client.CertificateTrustStore;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.mods.ModpackLoader;

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
		if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER) {
			SelfUpdater.update();
			return;
		}

		Jsons.ConnectionInfo storedConnectionInfo = null;
		if (clientConfig.selectedModpackId != null && !clientConfig.selectedModpackId.isBlank()) {
			if (!ModpackId.isValid(clientConfig.selectedModpackId)) {
				LOGGER.error("Ignoring invalid selected modpack ID: {}", clientConfig.selectedModpackId);
				clientConfig.selectedModpackId = "";
				ConfigTools.save(clientConfigFile, clientConfig);
			} else {
				storedConnectionInfo = clientConfig.modpackConnections.get(clientConfig.selectedModpackId);
				selectedModpackDir = ModpackUtils.getModpackPath(clientConfig.selectedModpackId);
			}
		}

		if (storedConnectionInfo == null || !storedConnectionInfo.isComplete()) {
			SelfUpdater.update();
			LegacyClientCacheUtils.deleteDummyFiles();
			return;
		}

		String expectedFingerprint = CertificateTrustStore.getFingerprint(storedConnectionInfo.origin);
		Jsons.ConnectionInfo connectionInfo = new Jsons.ConnectionInfo(storedConnectionInfo.origin, storedConnectionInfo.endpoint,
				storedConnectionInfo.requiresMagic, expectedFingerprint, null);
		Secrets.Secret secret = SecretsStore.getClientSecret(storedConnectionInfo.origin);

		// When update-on-launch is disabled, just load the already-installed
		// modpack: don't contact the server and don't reconcile local files,
		// so the user can freely add/remove mods (e.g. a binary search).
		if (!clientConfig.updateSelectedModpackOnLaunch) {
			if (Files.isDirectory(selectedModpackDir)) {
				loadLocalModpack(connectionInfo, secret);
			} else {
				SelfUpdater.update();
				LegacyClientCacheUtils.deleteDummyFiles();
			}
			return;
		}

		var optionalLatestModpackContent = ModpackUtils.requestServerModpackContent(connectionInfo, secret, false);
		var latestModpackContent = ConfigTools.loadModpackContent(selectedModpackDir.resolve(hostModpackContentFile.getFileName()));
		if (optionalLatestModpackContent.isPresent()) {
			latestModpackContent = optionalLatestModpackContent.get();
			if (!Objects.equals(clientConfig.selectedModpackId, latestModpackContent.modpackId)) {
				LOGGER.error("Selected modpack manifest changed ID from {} to {}", clientConfig.selectedModpackId, latestModpackContent.modpackId);
				loadLocalModpack(connectionInfo, secret);
				return;
			}
			selectedModpackDir = ModpackUtils.getModpackPath(latestModpackContent.modpackId);
			if (SelfUpdater.update(latestModpackContent)) return;
		}

		LegacyClientCacheUtils.deleteDummyFiles();
		new ModpackUpdater(latestModpackContent, connectionInfo, secret, selectedModpackDir).processModpackUpdate(null);
	}

	private void loadLocalModpack(Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret) {
		LegacyClientCacheUtils.deleteDummyFiles();
		var localModpackContent = ConfigTools.loadModpackContent(selectedModpackDir.resolve(hostModpackContentFile.getFileName()));
		try {
			new ModpackUpdater(localModpackContent, connectionInfo, secret, selectedModpackDir).loadModpack();
		} catch (Exception e) {
			LOGGER.error("Failed to load local modpack", e);
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
		LOADER = LOADER_MANAGER.getPlatformType().toString().toLowerCase(Locale.ROOT);
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
			var clientConfigVersion = ConfigTools.softLoad(clientConfigFile, Jsons.VersionConfigField.class);
			if (clientConfigVersion != null && clientConfigVersion.DO_NOT_CHANGE_IT < 3) {
				clientConfig = new Jsons.ClientConfigFieldsV3();
				LOGGER.warn("Legacy client config detected. Stable modpack IDs require a one-time modpack redownload.");
				LOGGER.warn("Old name-based modpack directories were left untouched and can be removed manually after the new modpack is installed.");
			} else {
				clientConfig = ConfigTools.load(clientConfigFile, Jsons.ClientConfigFieldsV3.class);
			}
		} else {
			// TODO: when connecting to the new server which provides modpack different modpack, ask the user if they want, stop using overrides
			LOGGER.warn("You are using unofficial {} mod", MOD_ID);
			LOGGER.warn("Using client config overrides! Editing the {} file will have no effect", clientConfigFile);
			LOGGER.warn("Remove the {} file from inside the jar or remove and download fresh {} mod jar from modrinth/curseforge",
					clientConfigFileOverrideResource, MOD_ID);
			var overrideVersion = ConfigTools.load(clientConfigOverride, Jsons.VersionConfigField.class);
			if (overrideVersion == null || overrideVersion.DO_NOT_CHANGE_IT < 3) {
				throw new IllegalStateException("Legacy client config overrides are unsupported; install an unmodified AutoModpack jar");
			}
			clientConfig = ConfigTools.load(clientConfigOverride, Jsons.ClientConfigFieldsV3.class);
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
						serverConfigV2.advertisedEndpointHost = "";
					} else {
						serverConfigV2.advertisedEndpointHost = AddressHelpers.parseOrigin(serverConfigV1.hostIp).getHostString();
					}

					if (serverConfigV1.hostModpackOnMinecraftPort) {
						serverConfigV2.bindPort = -1;
						serverConfigV2.advertisedEndpointPort = -1;
					} else {
						serverConfigV2.bindPort = serverConfigV1.hostPort;
						serverConfigV2.advertisedEndpointPort = serverConfigV1.hostPort;
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

			ConfigUtils.normalizeServerConfig(serverConfig);

			// Save changes
			ConfigTools.save(serverConfigFile, serverConfig);
		}

		if (clientConfig != null) {
			if (clientConfig.modpackConnections == null) clientConfig.modpackConnections = new HashMap<>();
			if (clientConfig.selectedModpackId == null) clientConfig.selectedModpackId = "";
			if (clientConfigOverride == null) ConfigTools.save(clientConfigFile, clientConfig);
		}

		knownHosts = ConfigTools.load(knownHostsFile, Jsons.KnownHostsFields.class);
		if (knownHosts != null && knownHosts.hosts == null) knownHosts.hosts = new HashMap<>();

		try {
			Files.createDirectories(privateDir);
			String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
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

		if (serverConfig == null || clientConfig == null) throw new RuntimeException("Failed to load config!");

		LOGGER.info("Loaded config! took {}ms", System.currentTimeMillis() - startTime);
	}
}
