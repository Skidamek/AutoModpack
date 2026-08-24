package pl.skidam.automodpack_loader_core;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.CREDENTIALS_DIR;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_CONFIG_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_DIR;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.BootstrapInstaller;
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
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdateReplanRequiredException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_loader_core.client.CertificateTrustStore;
import pl.skidam.automodpack_loader_core.client.ClientOfflineRepair;
import pl.skidam.automodpack_loader_core.client.ClientPendingUpdateRecovery;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.mods.ModpackLoader;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class Preload {
	private ClientStorage storage;
	private boolean trustedBootstrapApply;

	public Preload() {
		try {
			long start = System.currentTimeMillis();
			LOGGER.info("Prelaunching AutoModpack...");
			storage = ClientStorage.open(GameDirectory.current());
			initializeConstants();
			loadConfigs();
			recoverPendingRepair();
			recoverPendingTransaction();
			if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT) importBootstrap();
			updateAll();
			LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private void recoverPendingRepair() throws IOException {
		if (!Files.exists(storage.repairJournalFile(), LinkOption.NOFOLLOW_LINKS)) return;
		new ClientOfflineRepair(storage, MODPACK_LOADER).recover()
				.ifPresent(receipt -> LOGGER.info("Recovered offline repair for {} (complete: {})", receipt.before().modpackId(), receipt.complete()));
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

		try {
			UpdateTransactionExecutor executor = UpdateTransactionSupport.executor();
			UpdateTransactionExecutor.Execution execution;
			boolean replanned = false;
			if (executor.hasMutableInputDrift(transaction) && !executor.projectionPublicationStarted(transaction)) {
				execution = replanPendingTransaction(transaction);
				replanned = true;
			} else {
				try {
					execution = executor.recoverLatest();
				} catch (UpdateReplanRequiredException e) {
					execution = replanPendingTransaction(transaction);
					replanned = true;
				}
			}
			if (execution.replanRequired()) {
				execution = replanPendingTransaction(execution.transaction() == null ? transaction : execution.transaction());
				replanned = true;
			}
			if (!replanned && execution.success() && executor.hasMutableInputDrift(transaction)) execution = replanPendingTransaction(transaction);
			if (execution.replanRequired()) throw new UpdateReplanRequiredException(execution.blockedPath(), "Pending update still requires a fresh plan");
			finishPendingRecovery(execution, transaction);
		} catch (UpdateReplanRequiredException e) {
			throw e;
		} catch (IOException | RuntimeException e) {
			quarantineTransaction(e);
		}
	}

	private UpdateTransactionExecutor.Execution replanPendingTransaction(UpdateTransaction transaction) throws IOException {
		try {
			return ClientPendingUpdateRecovery.replan(storage, transaction, MODPACK_LOADER, LOADER);
		} catch (IOException e) {
			throw new UpdateReplanRequiredException(null, "Pending update could not be replanned; its durable mailbox was retained", e);
		}
	}

	private void finishPendingRecovery(UpdateTransactionExecutor.Execution execution, UpdateTransaction original) throws IOException {
		if (!execution.success()) {
			DetachedUpdateHelper.launch();
			new ReLauncher(UpdateType.UPDATE, null).restart(true);
			UpdateTransaction deferred = execution.transaction() == null ? original : execution.transaction();
			throw new UpdateDeferredException(deferred.transactionId, execution.blockedPath(), execution.message());
		}
		UpdateTransaction recovered = execution.transaction() == null ? original : execution.transaction();
		if (recovered.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			clientConfig = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
					.orElseThrow(() -> new ConfigTools.ConfigException("Recovered client config is missing"));
		}
		LOGGER.info("Recovered update transaction {}", recovered.transactionId);
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
				clientConfig = clientConfig.withSelectedModpackId("");
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
		// A trusted bootstrap file is an explicit install request and still applies.
		if (!clientConfig.updateSelectedModpackOnLaunch && !trustedBootstrapApply) {
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

		ModpackUpdater updater = new ModpackUpdater(selectedTarget, connectionInfo, secret, storage, downloadClient);
		if (trustedBootstrapApply) updater.applyTrustedInstall();
		else updater.processModpackUpdate(null);
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
		serverConfig = ConfigTools.readOrCreate(SERVER_CONFIG_FILE, ServerConfigJsons.ServerConfigFieldsV3.class, ServerConfigJsons.ServerConfigFieldsV3::new);

		if (serverConfig != null) {
			String serverConfigBefore = ConfigTools.GSON.toJson(serverConfig);
			if (serverConfig.acceptedLoaders == null) {
				serverConfig.acceptedLoaders = new HashSet<>(Set.of(LOADER));
			} else {
				serverConfig.acceptedLoaders.add(LOADER);
			}

			ConfigUtils.normalizeServerConfig(serverConfig);
			if (!serverConfigBefore.equals(ConfigTools.GSON.toJson(serverConfig))) writeConfig(SERVER_CONFIG_FILE, serverConfig);
		}

		try {
			Files.createDirectories(SERVER_DIR);
			Files.createDirectories(CREDENTIALS_DIR);
		} catch (IOException e) {
			LOGGER.error("Failed to create AutoModpack state roots", e);
		}

		if (serverConfig == null || clientConfig == null) throw new RuntimeException("Failed to load config!");

		LOGGER.info("Loaded config! took {}ms", System.currentTimeMillis() - startTime);
	}

	private void importBootstrap() {
		BootstrapInstaller.importIfPresent(storage, clientConfig).ifPresent(receipt -> {
			clientConfig = receipt.clientConfig();
			trustedBootstrapApply = receipt.installsModpack() && receipt.hasSecret();
		});
	}
}
