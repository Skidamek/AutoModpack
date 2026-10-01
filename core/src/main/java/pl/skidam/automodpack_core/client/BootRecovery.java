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
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateCommit;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdateReplanRequiredException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
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
			UpdateCommit.sweepUnpinnedPublicationDirectories(storage);
			return;
		}

		// The canonical reader asides unusable content as *.corrupt- evidence and returns null; physical read trouble propagates and crashes loudly.
		UpdateTransaction transaction = UpdateTransaction.read(storage.transactionFile());
		if (transaction == null) {
			UpdateRecovery.clearDeferredGuard(storage);
			UpdateCommit.sweepUnpinnedPublicationDirectories(storage);
			return;
		}

		LOGGER.info("Recovering pending update transaction {} (purpose {}, phase {}, recorded result {}, operation {}, path {}, message {})", transaction.transactionId, transaction.purpose,
				transaction.phase, transaction.resultStatus, transaction.resultOperation, transaction.resultPath, transaction.resultMessage);

		try {
			UpdateCommit commits = new UpdateCommit(storage);
			UpdateCommit.Outcome outcome = commits.run(new UpdateCommit.Recover(failure -> rebuildPending(failure.transaction())));
			finishPendingRecovery(outcome, transaction, commits);
		} catch (UpdateDeferredException e) {
			// The deferred restart owns this transaction now: ReLauncher exits the process in every preload path, so
			// reaching this arm means that contract broke. Rethrow rather than let the quarantine below undo a
			// handoff the deferred path intentionally kept.
			throw e;
		} catch (IOException | RuntimeException e) {
			// Retiring a mid-apply journal without restoring the last good tree would let the next boot sweep backup/, the only full copy of it.
			// A detached helper from a deferred restart may still be retrying; the aside makes its next attempt see nothing pending, so the race converges.
			new UpdateCommit(storage).revertUnfinalizedPublication(transaction);
			DurableFiles.setAside(storage.transactionFile(), "Persisted update transaction", e);
		}
	}

	/** The boot rebuild policy: a pending transaction is rebuilt from what the player now wants; a rebuild failure is terminal, never a crash. */
	private UpdateCommit.Built rebuildPending(UpdateTransaction pending) throws IOException {
		try {
			return UpdateAttempt.pendingRebuild(storage, pending, MODPACK_LOADER, LOADER);
		} catch (UpdateReplanRequiredException e) {
			throw e;
		} catch (IOException e) {
			throw new UpdateReplanRequiredException(null, "Pending update could not be replanned", e);
		}
	}

	private void finishPendingRecovery(UpdateCommit.Outcome outcome, UpdateTransaction original, UpdateCommit commits) throws IOException {
		UpdateTransaction deferred = outcome instanceof UpdateCommit.Blocked blocked ? blocked.transaction() : original;
		boolean done = outcome instanceof UpdateCommit.Applied || outcome instanceof UpdateCommit.Idle;
		if (!done) {
			UpdateRecovery.RecoveryAttempt attempt = UpdateRecovery.blockedRecovery(storage, deferred, outcome, () -> commits.run(new UpdateCommit.Recover(null)));
			outcome = attempt.outcome();
			deferred = attempt.deferred();
			done = outcome instanceof UpdateCommit.Applied || outcome instanceof UpdateCommit.Idle;
			if (attempt.reverted()) {
				rolledBackStuckUpdate = true;
				return;
			}
			if (!done && outcome instanceof UpdateCommit.Blocked blocked) {
				String held = blocked instanceof UpdateCommit.Deferred deferral ? deferral.held() : null;
				new ReLauncher(UpdateType.UPDATE, null, UpdateRecovery.deferredPopupMessage(held)).restart(true);
				throw new UpdateDeferredException(deferred.transactionId, blocked.blockedPath(), blocked.message());
			}
		}
		UpdateRecovery.clearDeferredGuard(storage);
		if (deferred.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			clientConfig = ReconfConfigs.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
					.orElseThrow(() -> new ConfigTools.ConfigException("Recovered client config is missing"));
		}
		LOGGER.info("Recovered update transaction {}", deferred.transactionId);
	}

	private void importBootstrap() {
		BootstrapInstaller.importIfPresent(storage, clientConfig).ifPresent(receipt -> {
			clientConfig = receipt.clientConfig();
			trustedBootstrapApply = receipt.installsModpack() && receipt.hasSecret();
		});
	}
}
