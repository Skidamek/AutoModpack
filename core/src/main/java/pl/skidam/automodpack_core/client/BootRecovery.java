package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOADER;
import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.MODPACK_LOADER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

import pl.skidam.automodpack_core.config.BootstrapInstaller;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ReconfConfigs;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdateReplanRequiredException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.DurableFiles;

/**
 * The client boot ordering: load the config, recover the offline repair, recover the pending update transaction, and
 * import a bootstrap install - in that order, once, before any update work runs. Preload is its thin loader adapter;
 * the deferred-transaction policy itself lives in {@link UpdateRecovery}.
 */
public final class BootRecovery {
	private final ClientStorage storage;
	private ClientConfigJsons.ClientConfigFieldsV3 clientConfig;
	private boolean trustedBootstrapApply;
	private boolean rolledBackStuckUpdate;

	public BootRecovery(ClientStorage storage) {
		this.storage = storage;
	}

	/** What the boot phase needs to decide how the launch proceeds. */
	public record BootDecision(ClientConfigJsons.ClientConfigFieldsV3 clientConfig, boolean trustedBootstrapApply, boolean rolledBackStuckUpdate) {}

	public BootDecision recover() throws IOException {
		loadClientConfig();
		recoverPendingRepair();
		recoverPendingTransaction();
		importBootstrap();
		return new BootDecision(clientConfig, trustedBootstrapApply, rolledBackStuckUpdate);
	}

	private void loadClientConfig() {
		long startTime = System.currentTimeMillis();
		clientConfig = ReconfConfigs.readOrCreate(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class, ClientConfigJsons.ClientConfigFieldsV3::new);
		if (clientConfig == null) throw new RuntimeException("Failed to load config!");
		LOGGER.info("Loaded config! took {}ms", System.currentTimeMillis() - startTime);
	}

	private void recoverPendingRepair() throws IOException {
		if (!Files.exists(storage.repairJournalFile(), LinkOption.NOFOLLOW_LINKS)) return;
		try {
			new ClientOfflineRepair(storage, MODPACK_LOADER).recover()
					.ifPresent(receipt -> LOGGER.info("Recovered offline repair for {} (complete: {})", receipt.before().modpackId(), receipt.complete()));
		} catch (IOException | RuntimeException e) {
			// The journal can name state that no longer exists (its generation was checked out, an editable
			// file changed). An unresumable repair must not boot-loop the game; the aside is the receipt and
			// the repair can be re-run from the installed-pack screens.
			DurableFiles.setAside(storage.repairJournalFile(), "Offline repair journal", e);
		}
	}

	private void recoverPendingTransaction() throws IOException {
		if (!Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) {
			UpdateRecovery.clearDeferredGuard(storage);
			UpdateTransactionExecutor.sweepUnpinnedPublicationDirectories(storage);
			return;
		}

		// The canonical reader asides unusable content as *.corrupt- evidence and returns null; physical read trouble propagates and crashes loudly.
		UpdateTransaction transaction = UpdateTransaction.read(storage.transactionFile());
		if (transaction == null) {
			UpdateRecovery.clearDeferredGuard(storage);
			UpdateTransactionExecutor.sweepUnpinnedPublicationDirectories(storage);
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
		if (!execution.success()) {
			UpdateRecovery.RecoveryAttempt attempt = UpdateRecovery.blockedRecovery(storage, deferred, execution, () -> UpdateTransactionSupport.executor().recoverLatest());
			execution = attempt.execution();
			deferred = attempt.deferred();
			if (attempt.reverted()) {
				rolledBackStuckUpdate = true;
				return;
			}
			if (!execution.success()) {
				new ReLauncher(UpdateType.UPDATE, null, UpdateRecovery.deferredPopupMessage(execution.held())).restart(true);
				throw new UpdateDeferredException(deferred.transactionId, execution.blockedPath(), execution.message());
			}
		}
		UpdateRecovery.clearDeferredGuard(storage);
		if (deferred.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			clientConfig = ReconfConfigs.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
					.orElseThrow(() -> new ConfigTools.ConfigException("Recovered client config is missing"));
		}
		LOGGER.info("Recovered update transaction {}", deferred.transactionId);
	}

	private void quarantineTransaction(Exception reason) throws IOException {
		DurableFiles.setAside(storage.transactionFile(), "Persisted update transaction", reason);
	}

	private void importBootstrap() {
		BootstrapInstaller.importIfPresent(storage, clientConfig).ifPresent(receipt -> {
			clientConfig = receipt.clientConfig();
			trustedBootstrapApply = receipt.installsModpack() && receipt.hasSecret();
		});
	}
}
