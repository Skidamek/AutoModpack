package pl.skidam.automodpack_core;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LOG_FILE;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.client.ClientOfflineRepair;
import pl.skidam.automodpack_core.client.DetachedUpdateHelper;
import pl.skidam.automodpack_core.client.ManifestFetcher;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.ReLauncher;
import pl.skidam.automodpack_core.client.SelfUpdater;
import pl.skidam.automodpack_core.client.StoredModpackConnection;
import pl.skidam.automodpack_core.client.UpdateAttempt;
import pl.skidam.automodpack_core.client.UpdateTransactionSupport;
import pl.skidam.automodpack_core.client.UpdateType;
import pl.skidam.automodpack_core.config.BootstrapInstaller;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.SelfUpdateSwap;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdateReplanRequiredException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_core.utils.DurableFiles;

public class Preload {
	// Three deferred restarts for the same transaction id, then the next failed recover rolls back. The id is the episode; the count does not expire.
	private static final int MAX_DEFERRED_RESTARTS = 3;
	// AWT preload dialog; Minecraft locale files are not loaded yet.
	private static final String DEFERRED_POPUP_MESSAGE = "The modpack update paused on a busy file. Restart again; if this window keeps appearing, please send your latest log file.";

	private ClientStorage storage;
	private boolean trustedBootstrapApply;
	private boolean rolledBackStuckUpdate;

	/** The modpack loader is supplied, not handed over: its class initialization reads {@link Constants#LOADER_MANAGER}, so it must be constructed only after that is installed. */
	public Preload(LoaderManagerService loaderManager, Supplier<ModpackLoaderService> modpackLoader) {
		try {
			long start = System.currentTimeMillis();
			LOGGER.info("Prelaunching AutoModpack...");
			initializeConstants(loaderManager, modpackLoader);
			recoverPendingSelfUpdate();
			if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT) {
				storage = ClientStorage.open(GameDirectory.current());
				loadClientConfig();
				recoverPendingRepair();
				recoverPendingTransaction();
				importBootstrap();
			} else {
				serverConfig = ConfigUtils.loadOrCreateServerConfig();
			}
			updateAll();
			LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	/** Both roles run their real jars from mods/, so the instance-level swap recovers before any role machinery wakes up. */
	private void recoverPendingSelfUpdate() {
		Path gameDirectory = GameDirectory.current();
		try {
			SelfUpdateSwap.recover(gameDirectory, DataRootResolver.resolve(gameDirectory));
		} catch (IOException e) {
			throw new IllegalStateException("Cannot recover the pending AutoModpack self-update", e);
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
		if (!Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) {
			deferredRecoveryGuard().clear();
			return;
		}

		// The canonical reader asides unusable content as *.corrupt- evidence and returns null; physical read trouble propagates and crashes loudly.
		UpdateTransaction transaction = UpdateTransaction.read(storage.transactionFile());
		if (transaction == null) {
			deferredRecoveryGuard().clear();
			return;
		}

		LOGGER.info("Recovering pending update transaction {} (purpose {}, phase {}, recorded result {}, operation {}, path {}, message {})", transaction.transactionId, transaction.purpose,
				transaction.phase, transaction.resultStatus, transaction.resultOperation, transaction.resultPath, transaction.resultMessage);

		try {
			UpdateTransactionExecutor executor = UpdateTransactionSupport.executor();
			UpdateTransactionExecutor.Execution execution = executor.commitWithReplan(
					() -> recoverPendingExecution(executor, transaction),
					failedExecution -> replanPendingExecution(transaction, failedExecution));
			finishPendingRecovery(execution, transaction);
		} catch (UpdateReplanRequiredException e) {
			throw e;
		} catch (IOException | RuntimeException e) {
			quarantineTransaction(e);
		}
	}

	/** The preload recovery policy: a pending update replans proactively on pre-commit drift, and drift found after a successful recovery forces one replan whose replan-required result is terminal. */
	private UpdateTransactionExecutor.Execution recoverPendingExecution(UpdateTransactionExecutor executor, UpdateTransaction transaction) throws IOException {
		if (executor.hasMutableInputDrift(transaction) && !ClientProjectionView.publicationStarted(storage, transaction)) return replanPendingTransaction(transaction);
		UpdateTransactionExecutor.Execution execution;
		try {
			execution = executor.recoverLatest();
		} catch (UpdateReplanRequiredException e) {
			return replanPendingTransaction(transaction);
		}
		if (execution.success() && executor.hasMutableInputDrift(transaction)) {
			execution = replanPendingTransaction(transaction);
			if (execution.replanRequired()) throw new UpdateReplanRequiredException(execution.blockedPath(), "Pending update still requires a fresh plan");
		}
		return execution;
	}

	private UpdateTransactionExecutor.Execution replanPendingExecution(UpdateTransaction transaction, UpdateTransactionExecutor.Execution failedExecution) throws IOException {
		UpdateTransactionExecutor.Execution execution = replanPendingTransaction(failedExecution.transaction() == null ? transaction : failedExecution.transaction());
		if (execution.replanRequired()) throw new UpdateReplanRequiredException(execution.blockedPath(), "Pending update still requires a fresh plan");
		return execution;
	}

	private UpdateTransactionExecutor.Execution replanPendingTransaction(UpdateTransaction transaction) throws IOException {
		try {
			return UpdateAttempt.resume(storage, transaction, MODPACK_LOADER, LOADER);
		} catch (UpdateReplanRequiredException e) {
			throw e;
		} catch (IOException e) {
			throw new UpdateReplanRequiredException(null, "Pending update could not be replanned; its durable mailbox was retained", e);
		}
	}

	private void finishPendingRecovery(UpdateTransactionExecutor.Execution execution, UpdateTransaction original) throws IOException {
		UpdateTransaction deferred = execution.transaction() == null ? original : execution.transaction();
		if (!execution.success() && DetachedUpdateHelper.awaitRunningHelper()) {
			LOGGER.info("The detached update helper finished; retrying recovery of transaction {}", deferred.transactionId);
			UpdateTransactionExecutor executor = UpdateTransactionSupport.executor();
			execution = executor.recoverLatest();
			deferred = execution.transaction() == null ? original : execution.transaction();
		}
		if (!execution.success()) {
			logDeferredRecovery(deferred, execution);
			if (deferredRecoveryGuard().evaluateAndRecord(deferred.transactionId) == UpdateLoopDetector.Decision.SUPPRESS) {
				Path stuckJournal = UpdateTransactionSupport.executor().abandonStuckPublication(deferred);
				deferredRecoveryGuard().clear();
				rolledBackStuckUpdate = true;
				LOGGER.error("The same update transaction {} failed after {} deferred restarts; kept the last finalized generation and retired the transaction to {}", deferred.transactionId,
						MAX_DEFERRED_RESTARTS, stuckJournal.toAbsolutePath().normalize());
				LOGGER.error("If the update keeps failing, send that file together with {} and the latest log", GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize());
				return;
			}
			DetachedUpdateHelper.launch();
			new ReLauncher(UpdateType.UPDATE, null, DEFERRED_POPUP_MESSAGE).restart(true);
			throw new UpdateDeferredException(deferred.transactionId, execution.blockedPath(), execution.message());
		}
		deferredRecoveryGuard().clear();
		if (deferred.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			clientConfig = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
					.orElseThrow(() -> new ConfigTools.ConfigException("Recovered client config is missing"));
		}
		LOGGER.info("Recovered update transaction {}", deferred.transactionId);
	}

	private void logDeferredRecovery(UpdateTransaction transaction, UpdateTransactionExecutor.Execution execution) {
		LOGGER.error("The pending modpack update did not finish: transaction {} (purpose {}, phase {}) ended with status {}", transaction.transactionId, transaction.purpose, transaction.phase,
				execution.status());
		LOGGER.error("Blocked operation {}, blocked path {}, message {}", execution.operation(), execution.blockedPath(), execution.message());
		LOGGER.error("Journal-recorded result: status {}, operation {}, path {}, message {}", transaction.resultStatus, transaction.resultOperation, transaction.resultPath, transaction.resultMessage);
		LOGGER.error("The full transaction journal is at {}", storage.transactionFile().toAbsolutePath().normalize());
		LOGGER.error("The detached helper's own log, with its per-attempt recovery failures, is at {}", GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize());
	}

	private UpdateLoopDetector deferredRecoveryGuard() {
		return new UpdateLoopDetector(storage.stuckTransactionStateFile(), System::currentTimeMillis, MAX_DEFERRED_RESTARTS, null);
	}

	private void quarantineTransaction(Exception reason) throws IOException {
		DurableFiles.setAside(storage.transactionFile(), "Persisted update transaction", reason);
	}

	private void updateAll() {
		if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER) {
			SelfUpdater.update();
			return;
		}

		// A stuck update was just rolled back; this launch only boots the restored pack and the next one syncs normally again.
		if (rolledBackStuckUpdate) {
			LOGGER.info("Booting the restored modpack without contacting the server");
			if (hasActiveProjection()) loadLocalModpack(null, null);
			return;
		}

		StoredModpackConnection.Seeded seeded = null;
		if (clientConfig.hasSelectedModpack()) {
			if (!ModpackId.isValid(clientConfig.selectedModpackId)) {
				LOGGER.error("Ignoring invalid selected modpack ID: {}", clientConfig.selectedModpackId);
				clientConfig = clientConfig.withSelectedModpackId("");
				writeConfig(storage.clientConfigFile(), clientConfig);
			} else {
				try {
					seeded = StoredModpackConnection.seed(storage, clientConfig.selectedModpackId);
				} catch (IOException e) {
					LOGGER.error("Failed to load selected modpack connection state", e);
				}
			}
		}

		if (seeded == null || !seeded.connection().isComplete()) {
			if (hasActiveProjection()) loadLocalModpack(null, null);
			else SelfUpdater.update();
			return;
		}

		ConnectionJsons.ConnectionInfo connectionInfo = seeded.connection();
		Secrets.Secret secret = seeded.secret();
		if (seeded.anonymousSecret()) LOGGER.info("No saved secret for seeded/selected origin {}; using an anonymous preload secret", AddressHelpers.formatAddress(connectionInfo.origin));

		// updateSelectedModpackOnLaunch=false loads the current projection and does not contact the
		// server, so extra jars in mods/ stay put (binary search, pinning experiments). A trusted
		// bootstrap file is an explicit install request and still applies.
		if (!clientConfig.updateSelectedModpackOnLaunch && !trustedBootstrapApply) {
			if (hasActiveProjection()) {
				loadLocalModpack(connectionInfo, secret);
			} else {
				SelfUpdater.update();
			}
			return;
		}

		// A detached pack holds local sovereignty: launch boots it as-is, fetching nothing, applying nothing, protecting nothing.
		if (!trustedBootstrapApply && isDetachedFromServer()) {
			LOGGER.info("Selected modpack is detached; booting the local pack without server sync");
			if (hasActiveProjection()) {
				loadLocalModpack(connectionInfo, secret);
			} else {
				SelfUpdater.update();
			}
			return;
		}

		var manifestResult = ManifestFetcher.requestServerModpackContent(storage, connectionInfo, secret, false);
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
		else updater.processModpackUpdate(true);
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
			// An unset selection is the fresh install: nothing selected, nothing to load, nothing worth saying.
			if (!clientConfig.hasSelectedModpack()) return false;
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

	/** An unreadable state is not detachment; the normal launch path then hits its own loud failure for the corrupt state. */
	private boolean isDetachedFromServer() {
		try {
			return new ClientGenerationStore(storage).isDetached(clientConfig.selectedModpackId);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Cannot read the detached flag of the selected modpack", e);
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

	private void initializeConstants(LoaderManagerService loaderManager, Supplier<ModpackLoaderService> modpackLoader) {
		// Initialize global variables
		preload = true;
		PRELOAD_TIME = System.currentTimeMillis();
		LOADER_MANAGER = loaderManager;
		MODPACK_LOADER = modpackLoader.get();
		MC_VERSION = LOADER_MANAGER.getModVersion("minecraft");
		LOADER_VERSION = LOADER_MANAGER.getLoaderVersion();
		LOADER = LOADER_MANAGER.getPlatformType().toString().toLowerCase(Locale.ROOT);
		THIS_MOD_JAR = JarUtils.getJarPath(this.getClass());
		AM_VERSION = FileInspection.getModVersion(THIS_MOD_JAR);
	}

	private void loadClientConfig() {
		long startTime = System.currentTimeMillis();
		clientConfig = ConfigTools.readOrCreate(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class, ClientConfigJsons.ClientConfigFieldsV3::new);
		if (clientConfig == null) throw new RuntimeException("Failed to load config!");
		LOGGER.info("Loaded config! took {}ms", System.currentTimeMillis() - startTime);
	}

	private void importBootstrap() {
		BootstrapInstaller.importIfPresent(storage, clientConfig).ifPresent(receipt -> {
			clientConfig = receipt.clientConfig();
			trustedBootstrapApply = receipt.installsModpack() && receipt.hasSecret();
		});
	}
}
