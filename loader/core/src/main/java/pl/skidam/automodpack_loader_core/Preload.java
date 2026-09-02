package pl.skidam.automodpack_loader_core;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.BootstrapConfig;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_loader_core.client.CertificateTrustStore;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.mods.ModpackLoader;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class Preload {
	private ClientStorage storage;

	public Preload() {
		try {
			long start = System.currentTimeMillis();
			LOGGER.info("Prelaunching AutoModpack...");
			storage = ClientStorage.fromGameDirectory(SmartFileUtils.CWD);
			initializeConstants();
			loadConfigs();
			DetachedUpdateHelper.cleanupOldHelperJars();
			recoverPendingTransaction();
			if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT) importBootstrap();
			updateAll();
			LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private static void writeConfig(Path path, Object value) {
		try {
			ConfigTools.writeAtomic(path, value);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save configuration " + path.toAbsolutePath().normalize(), e);
		}
	}

	private void recoverPendingTransaction() throws IOException {
		if (!Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) return;

		UpdateTransaction transaction;
		try {
			transaction = ConfigTools.read(storage.transactionFile(), UpdateTransaction.class)
					.orElseThrow(() -> new ConfigTools.ConfigException("Transaction file is missing"));
		} catch (RuntimeException e) {
			quarantineTransaction(e);
			return;
		}

		UpdateTransactionExecutor executor;
		try {
			executor = UpdateTransactionSupport.executor();
			executor.validate(transaction);
		} catch (IOException | RuntimeException e) {
			quarantineTransaction(e);
			return;
		}

		UpdateTransactionExecutor.Execution execution = executor.recover(transaction);
		if (!execution.success()) {
			DetachedUpdateHelper.launch(transaction);
			new ReLauncher(UpdateType.UPDATE, null).restart(true);
			throw new UpdateDeferredException(transaction.transactionId, execution.blockedPath(), execution.message());
		}
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			clientConfig = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
					.orElseThrow(() -> new ConfigTools.ConfigException("Recovered client config is missing"));
		}
		LOGGER.info("Recovered update transaction {}", transaction.transactionId);
	}

	private void quarantineTransaction(Exception reason) throws IOException {
		Files.createDirectories(storage.clientDirectory());
		Path quarantine = storage.clientDirectory().resolve("update-transaction.invalid-" + UUID.randomUUID() + ".json");
		Files.move(storage.transactionFile(), quarantine, StandardCopyOption.REPLACE_EXISTING);
		LOGGER.error("Quarantined invalid update transaction at {}", quarantine.toAbsolutePath().normalize(), reason);
	}

	private void updateAll() {
		if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER) {
			SelfUpdater.update();
			return;
		}

		ConnectionJsons.ConnectionInfo storedConnectionInfo = null;
		if (clientConfig.selectedModpackId != null && !clientConfig.selectedModpackId.isBlank()) {
			if (!ModpackId.isValid(clientConfig.selectedModpackId)) {
				LOGGER.error("Ignoring invalid selected modpack ID: {}", clientConfig.selectedModpackId);
				clientConfig.selectedModpackId = "";
				writeConfig(storage.clientConfigFile(), clientConfig);
			} else {
				try {
					storedConnectionInfo = ConnectionStore.getConnection(storage, clientConfig.selectedModpackId);
				} catch (IOException e) {
					LOGGER.error("Failed to load selected modpack connection state", e);
				}
			}
		}

		if (storedConnectionInfo == null || !storedConnectionInfo.isComplete()) {
			if (hasActiveProjection()) loadLocalModpack(null, null);
			else SelfUpdater.update();
			return;
		}

		String expectedFingerprint = CertificateTrustStore.getFingerprint(storedConnectionInfo.origin);
		ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(storedConnectionInfo.origin, storedConnectionInfo.endpoint,
				storedConnectionInfo.connectionMode, expectedFingerprint, null);
		Secrets.Secret secret = SecretsStore.getClientSecret(storage, clientConfig.selectedModpackId, storedConnectionInfo.origin);
		if (secret == null) {
			secret = Secrets.anonymousSecret();
			LOGGER.info("No saved secret for seeded/selected origin {}; using an anonymous preload secret", AddressHelpers.formatAddress(storedConnectionInfo.origin));
		}

		// When update-on-launch is disabled, just load the already-installed
		// modpack: don't contact the server and don't reconcile local files,
		// so the user can freely add/remove mods (e.g. a binary search).
		if (!clientConfig.updateSelectedModpackOnLaunch) {
			if (hasActiveProjection()) {
				loadLocalModpack(connectionInfo, secret);
			} else {
				SelfUpdater.update();
			}
			return;
		}

		var manifestResult = ModpackUtils.requestServerModpackContent(storage, connectionInfo, secret, false);
		SelectedModpackTarget selectedTarget = loadStoredTarget();
		DownloadClient downloadClient = null;
		if (manifestResult.successful()) {
			downloadClient = manifestResult.client();
			try {
				selectedTarget = SelectedModpackTarget.prepare(manifestResult.content(), new ClientSelectionStore(storage.selectionFile()), ClientPlatform.current());
			} catch (RuntimeException e) {
				LOGGER.error("Failed to resolve the downloaded modpack catalogue and group selection", e);
				downloadClient.close();
				loadLocalModpack(connectionInfo, secret);
				return;
			}
			ModpackJsons.ModpackContentFields latestModpackContent = selectedTarget.flatTarget();
			if (!Objects.equals(clientConfig.selectedModpackId, latestModpackContent.modpackId)) {
				LOGGER.error("Selected modpack catalogue changed ID from {} to {}", clientConfig.selectedModpackId, latestModpackContent.modpackId);
				downloadClient.close();
				loadLocalModpack(connectionInfo, secret);
				return;
			}
			if (SelfUpdater.update(latestModpackContent)) {
				downloadClient.close();
				return;
			}
		}
		if (selectedTarget == null) {
			loadLocalModpack(connectionInfo, secret);
			return;
		}

		new ModpackUpdater(selectedTarget, connectionInfo, secret, storage, downloadClient).processModpackUpdate(null);
	}

	private void loadLocalModpack(ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret) {
		if (!hasActiveProjection()) return;
		try {
			new ModpackUpdater(connectionInfo, secret, storage).loadModpack();
		} catch (Exception e) {
			LOGGER.error("Failed to load local modpack", e);
		}
	}

	private boolean hasActiveProjection() {
		try {
			if (!ModpackId.isValid(clientConfig.selectedModpackId)) {
				LOGGER.warn("Skipping active modpack load because the configured selected modpack ID is invalid: {}", clientConfig.selectedModpackId);
				return false;
			}
			if (!Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return false;
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null) {
				LOGGER.warn("Skipping active modpack load because the active projection has no active state");
				return false;
			}
			if (!clientConfig.selectedModpackId.equals(state.modpackId)) {
				LOGGER.warn("Skipping active modpack load because active state belongs to {}, but the selected modpack is {}", state.modpackId,
						clientConfig.selectedModpackId);
				return false;
			}
			return true;
		} catch (IOException e) {
			LOGGER.warn("Cannot read active client projection state", e);
			return false;
		}
	}

	private SelectedModpackTarget loadStoredTarget() {
		try {
			SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current()).orElse(null);
			if (target != null && !Objects.equals(clientConfig.selectedModpackId, target.manifest().modpackId())) {
				LOGGER.warn("Ignoring stored modpack target {} because the selected modpack is {}", target.manifest().modpackId(), clientConfig.selectedModpackId);
				return null;
			}
			return target;
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Failed to resolve the stored modpack catalogue and group selection", e);
			return null;
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
	}

	private void loadConfigs() {
		long startTime = System.currentTimeMillis();

		// load client config
		clientConfig = ConfigTools.readOrCreate(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class, ClientConfigJsons.ClientConfigFieldsV3::new);

		// load server config
		serverConfig = ConfigTools.readOrCreate(serverConfigFile, ServerConfigJsons.ServerConfigFieldsV3.class, ServerConfigJsons.ServerConfigFieldsV3::new);

		if (serverConfig != null) {
			String serverConfigBefore = ConfigTools.GSON.toJson(serverConfig);
			if (serverConfig.acceptedLoaders == null) {
				serverConfig.acceptedLoaders = new HashSet<>(Set.of(LOADER));
			} else {
				serverConfig.acceptedLoaders.add(LOADER);
			}

			ConfigUtils.normalizeServerConfig(serverConfig);
			if (!serverConfigBefore.equals(ConfigTools.GSON.toJson(serverConfig))) writeConfig(serverConfigFile, serverConfig);
		}

		try {
			storage.ensureRoots();
			Files.createDirectories(serverDir);
		} catch (IOException e) {
			LOGGER.error("Failed to create AutoModpack state roots", e);
		}

		if (serverConfig == null || clientConfig == null) throw new RuntimeException("Failed to load config!");

		LOGGER.info("Loaded config! took {}ms", System.currentTimeMillis() - startTime);
	}

	private void importBootstrap() {
		if (!Files.isRegularFile(storage.bootstrapFile())) return;

		ConnectionJsons.KnownHostsBootstrapFields fields = ConfigTools.read(storage.bootstrapFile(), ConnectionJsons.KnownHostsBootstrapFields.class)
				.orElseThrow(() -> new ConfigTools.ConfigException("Bootstrap file is not a regular file"));
		final BootstrapConfig.Validated bootstrap;
		try {
			bootstrap = BootstrapConfig.validate(fields);
		} catch (IllegalArgumentException e) {
			throw new ConfigTools.ConfigException("Invalid bootstrap file " + storage.bootstrapFile().toAbsolutePath().normalize(), e);
		}

		String originKey = AddressHelpers.formatAddress(bootstrap.origin());
		String previousSelectedModpackId = clientConfig.selectedModpackId;
		ClientConfigJsons.ClientConfigFieldsV3 updatedClientConfig = clientConfig;
		String targetModpackId = bootstrap.installsModpack() ? bootstrap.modpackId() : ModpackId.isValid(clientConfig.selectedModpackId) ? clientConfig.selectedModpackId : null;
		ConnectionJsons.ConnectionInfo previousConnection = null;
		ConnectionJsons.CertificateTrustEntry previousTrust;
		try {
			previousTrust = CertificateTrustStore.get(bootstrap.origin());
			CertificateTrustStore.save(bootstrap.origin(), bootstrap.fingerprint(), CertificateTrustStore.Reason.SEED);
			if (targetModpackId != null) {
				previousConnection = ConnectionStore.getConnection(storage, targetModpackId);
				if (bootstrap.installsModpack()) {
					ConnectionStore.saveConnection(storage, targetModpackId,
							new ConnectionJsons.ConnectionInfo(bootstrap.origin(), bootstrap.endpoint(), bootstrap.connectionMode(), null, null));
					updatedClientConfig = new ClientConfigJsons.ClientConfigFieldsV3(clientConfig);
					updatedClientConfig.selectedModpackId = targetModpackId;
					writeConfig(storage.clientConfigFile(), updatedClientConfig);
					clientConfig = updatedClientConfig;
				}
			}
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to import bootstrap connection state", e);
		}
		if (previousTrust == null) {
			LOGGER.info("Imported seeded certificate pin for origin {} ({})", originKey, NetUtils.shortenFingerprint(bootstrap.fingerprint()));
		} else {
			LOGGER.info("Replaced seeded certificate pin for origin {}: {} -> {}", originKey, NetUtils.shortenFingerprint(previousTrust.fingerprint),
					NetUtils.shortenFingerprint(bootstrap.fingerprint()));
		}
		if (bootstrap.installsModpack()) {
			String oldOrigin = previousConnection == null || previousConnection.origin == null ? "none" : AddressHelpers.formatAddress(previousConnection.origin);
			String oldEndpoint = previousConnection == null || previousConnection.endpoint == null ? "none" : AddressHelpers.formatAddress(previousConnection.endpoint);
			LOGGER.info("Seed selection {} -> {}; connection origin {} -> {}; endpoint {} -> {}", previousSelectedModpackId, targetModpackId, oldOrigin,
					AddressHelpers.formatAddress(bootstrap.origin()), oldEndpoint, AddressHelpers.formatAddress(bootstrap.endpoint()));
		}
		try {
			Files.delete(storage.bootstrapFile());
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Bootstrap state was saved but the bootstrap file could not be deleted", e);
		}
	}
}
