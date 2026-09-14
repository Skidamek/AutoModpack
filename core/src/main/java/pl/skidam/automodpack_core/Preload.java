package pl.skidam.automodpack_core;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.HELPER_LOG_FILE;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

import pl.skidam.automodpack_core.client.ClientLaunch;
import pl.skidam.automodpack_core.client.ClientOfflineRepair;
import pl.skidam.automodpack_core.client.DetachedUpdateHelper;
import pl.skidam.automodpack_core.client.ReLauncher;
import pl.skidam.automodpack_core.client.SelfUpdater;
import pl.skidam.automodpack_core.client.UpdateAttempt;
import pl.skidam.automodpack_core.client.UpdateTransactionSupport;
import pl.skidam.automodpack_core.client.UpdateType;
import pl.skidam.automodpack_core.config.BootstrapInstaller;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
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
	// Two deferred restarts for the same transaction id; the third failed recover rolls back to the last finalized generation. The id is the episode; the count does not expire.
	private static final int MAX_DEFERRED_RESTARTS = 2;
	// AWT preload dialog; Minecraft locale files are not loaded yet.
	private static final String DEFERRED_POPUP_MESSAGE = "The modpack update paused on a busy file. Close programs using the modpack folder, including File Explorer windows, then restart again; if this keeps appearing, please send your latest log file.";

	private ClientStorage storage;
	private boolean trustedBootstrapApply;
	private boolean rolledBackStuckUpdate;

	public Preload(LoaderManagerService loaderManager, ModpackLoaderService modpackLoader) {
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
			if (execution.success()) DetachedUpdateHelper.mirrorRecentRuns();
		}
		if (!execution.success()) {
			UpdateLoopDetector.Outcome loop = deferredRecoveryGuard().evaluateAndRecord(deferred.transactionId);
			logDeferredRecovery(deferred, execution, loop);
			if (loop.decision() == UpdateLoopDetector.Decision.SUPPRESS) {
				Path stuckJournal = UpdateTransactionSupport.executor().abandonStuckPublication(deferred);
				deferredRecoveryGuard().clear();
				rolledBackStuckUpdate = true;
				LOGGER.error("The same update transaction {} failed after {} deferred restarts; kept the last finalized generation and retired the transaction to {}", deferred.transactionId,
						loop.restarts(), stuckJournal.toAbsolutePath().normalize());
				LOGGER.error("If the update keeps failing, send that file together with {} and the latest log", GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize());
				return;
			}
			DetachedUpdateHelper.launch();
			new ReLauncher(UpdateType.UPDATE, null, deferredPopupMessage(execution.held())).restart(true);
			throw new UpdateDeferredException(deferred.transactionId, execution.blockedPath(), execution.message());
		}
		deferredRecoveryGuard().clear();
		if (deferred.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			clientConfig = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
					.orElseThrow(() -> new ConfigTools.ConfigException("Recovered client config is missing"));
		}
		LOGGER.info("Recovered update transaction {}", deferred.transactionId);
	}

	private void logDeferredRecovery(UpdateTransaction transaction, UpdateTransactionExecutor.Execution execution, UpdateLoopDetector.Outcome loop) {
		LOGGER.error("The pending modpack update did not finish: transaction {} (purpose {}, phase {}) ended with status {}", transaction.transactionId, transaction.purpose, transaction.phase,
				execution.status());
		LOGGER.error("Blocked operation {}, blocked path {}, message {}", execution.operation(), execution.blockedPath(), execution.message());
		LOGGER.error("Journal-recorded result: status {}, operation {}, path {}, message {}", transaction.resultStatus, transaction.resultOperation, transaction.resultPath, transaction.resultMessage);
		LOGGER.error("The full transaction journal is at {}", storage.transactionFile().toAbsolutePath().normalize());
		LOGGER.error("The detached helper's own log, with its per-attempt recovery failures, is at {}", GameDirectory.current().resolve(HELPER_LOG_FILE).toAbsolutePath().normalize());
		DetachedUpdateHelper.mirrorRecentRuns();
		if (loop.decision() == UpdateLoopDetector.Decision.RESTART)
			LOGGER.error("This is deferred restart {} of {} for the transaction; when they run out, the next failed launch rolls back to the last finalized generation", loop.restarts(), loop.maxRestarts());
	}

	private UpdateLoopDetector deferredRecoveryGuard() {
		return new UpdateLoopDetector(storage.stuckTransactionStateFile(), System::currentTimeMillis, MAX_DEFERRED_RESTARTS, null);
	}

	/** The generic guidance, plus the lock probe's holder receipt when the failure captured one. */
	private static String deferredPopupMessage(String held) {
		return held == null || held.isBlank() ? DEFERRED_POPUP_MESSAGE : DEFERRED_POPUP_MESSAGE + " Blocked paths: " + held + ".";
	}

	private void quarantineTransaction(Exception reason) throws IOException {
		DurableFiles.setAside(storage.transactionFile(), "Persisted update transaction", reason);
	}

	private void updateAll() {
		if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER) {
			SelfUpdater.update();
			return;
		}
		new ClientLaunch(storage, trustedBootstrapApply, rolledBackStuckUpdate).run();
	}

	private void initializeConstants(LoaderManagerService loaderManager, ModpackLoaderService modpackLoader) {
		preload = true;
		PRELOAD_TIME = System.currentTimeMillis();
		LOADER_MANAGER = loaderManager;
		MODPACK_LOADER = modpackLoader;
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
