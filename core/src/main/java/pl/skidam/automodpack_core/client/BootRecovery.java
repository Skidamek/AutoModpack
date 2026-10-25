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
import pl.skidam.automodpack_core.update.GeneratedCopyState;
import pl.skidam.automodpack_core.update.ProjectionRelaunch;
import pl.skidam.automodpack_core.update.ProjectionState;
import pl.skidam.automodpack_core.update.PublicationSweep;
import pl.skidam.automodpack_core.update.UpdateCommit;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdateReplanRequiredException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.DurableFiles;

/**
 * The client boot ordering: load the config, recover the offline repair, recover the pending update transaction,
 * rebuild the generated-copy record the active generation needs, and import a bootstrap install - in that order, once,
 * before any update work runs. Preload is its thin loader adapter; the deferred-transaction policy itself lives in
 * {@link UpdateRecovery}.
 */
public final class BootRecovery {
	private final ClientStorage storage;
	private ClientConfigJsons.ClientConfigFieldsV3 clientConfig;
	private boolean trustedBootstrapApply;
	private boolean bootAsIs;

	public BootRecovery(ClientStorage storage) {
		this.storage = storage;
	}

	/** What the boot phase needs to decide how the launch proceeds. */
	public record BootDecision(ClientConfigJsons.ClientConfigFieldsV3 clientConfig, boolean trustedBootstrapApply, boolean bootAsIs) {}

	public BootDecision recover() throws IOException {
		loadClientConfig();
		recoverPendingRepair();
		recoverPendingTransaction();
		settleProjection();
		relaunchIfScannedProjectionMoved();
		rebuildGeneratedCopies();
		importBootstrap();
		return new BootDecision(clientConfig, trustedBootstrapApply, bootAsIs);
	}

	/**
	 * P4 of {@link ProjectionState}: whatever recovery achieved, this boot never runs on owed projection work. A journal
	 * that still owes it - held paths after the restart budget, a failed replay, a helper that has not finished - keeps
	 * its PROJECTED phase and is rolled forward by a later run, while this boot gets the complete projection the
	 * convergence started from, restored from CAS. A restore that cannot finish leaves the state converging, and every
	 * loader gate then refuses the projection for this boot instead of loading a mixed one.
	 */
	private void settleProjection() {
		try {
			boolean owed;
			try {
				owed = ProjectionState.owed(storage);
			} catch (IOException physical) {
				// The writer's read records it and, once it has persisted across a boot, repairs it (P8).
				owed = true;
			}
			if (!owed) return;
			LOGGER.warn("The pending update still owes projection work; booting the complete projection it started from");
			if (!PublicationSweep.restoreFallback(storage)) LOGGER.error("The projection could not be restored; this boot runs without the modpack projection");
		} catch (IOException | RuntimeException e) {
			LOGGER.error("The projection could not be restored; this boot runs without the modpack projection", e);
		}
	}

	/**
	 * A directory-scanning loader (Kilt) takes {@code active/mods} before preload, so it got what the scan-time gate
	 * decided from its one read of the projection state. When recovery - this boot's, or a detached helper's that
	 * finished meanwhile - left a settled projection behind whose content is not what that gate handed out (it moved,
	 * or the gate refused a projection that is loadable now), this boot relaunches into it, once: a persisted one-shot
	 * guard bounds the relaunches whatever recovery keeps doing, and a boot that needs none clears it
	 * ({@link ProjectionRelaunch}). A boot that cannot relaunch never half-loads: {@code ProjectionLoader} refuses the
	 * projection the gate refused.
	 */
	private void relaunchIfScannedProjectionMoved() {
		if (ProjectionRelaunch.required(storage, ClientStorage.directoryScan())) new ReLauncher(UpdateType.UPDATE).restart(true);
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
			PublicationSweep.sweepUnpinnedDirectories(storage);
			return;
		}

		// The canonical reader asides unusable content as *.corrupt- evidence and returns null; physical read trouble propagates and crashes loudly.
		UpdateTransaction transaction = UpdateTransaction.read(storage.transactionFile());
		if (transaction == null) {
			UpdateRecovery.clearDeferredGuard(storage);
			PublicationSweep.sweepUnpinnedDirectories(storage);
			return;
		}

		LOGGER.info("Recovering pending update transaction {} (purpose {}, phase {}, recorded result {}, operation {}, path {}, message {})", transaction.transactionId, transaction.purpose,
				transaction.phase, transaction.resultStatus, transaction.resultOperation, transaction.resultPath, transaction.resultMessage);

		UpdateCommit commits = new UpdateCommit(storage);
		try {
			UpdateCommit.Outcome outcome = commits.run(new UpdateCommit.Recover(failure -> rebuildPending(failure.transaction())));
			finishPendingRecovery(outcome, transaction, commits);
		} catch (UpdateDeferredException e) {
			// The deferred restart owns this transaction now: ReLauncher exits the process in every preload path, so
			// reaching this arm means that contract broke. Rethrow rather than let the quarantine below undo a
			// handoff the deferred path intentionally kept.
			throw e;
		} catch (IOException | RuntimeException e) {
			UpdateTransaction failed = UpdateTransaction.read(storage.transactionFile());
			if (failed != null && ClientProjectionView.mutationStarted(storage, failed)) {
				// Only a finished apply untangles a started one: it stays owed, every boot rolls it forward again without a
				// restart, and the next sync plans on top of it, which also retires a journal no replay can ever finish.
				LOGGER.error("The pending update {} failed part-way and stays owed; booting what is installed until a replay or the next sync finishes it", failed.transactionId, e);
				nameUnscannedChanges(commits);
				return;
			}
			// A detached helper from a deferred restart may still be retrying; the aside makes its next attempt see nothing pending, so the race converges.
			DurableFiles.setAside(storage.transactionFile(), "Persisted update transaction", e);
			PublicationSweep.sweepUnpinnedDirectories(storage);
		}
	}

	/**
	 * The generated-copy record of the active generation, rebuilt from what is installed. It runs after the pending
	 * transaction is settled, because a replayed apply writes the record itself and a still-owed one leaves the last
	 * committed generation as the truth.
	 */
	private void rebuildGeneratedCopies() throws IOException {
		GeneratedCopyState.rebuildActive(storage);
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
		boolean done = outcome.settled();
		if (!done) {
			UpdateRecovery.RecoveryAttempt attempt = UpdateRecovery.blockedRecovery(storage, deferred, outcome, () -> commits.run(new UpdateCommit.Recover(null)));
			outcome = attempt.outcome();
			deferred = attempt.deferred();
			done = outcome.settled();
			if (attempt.bootAsIs()) {
				bootAsIs = true;
				nameUnscannedChanges(commits);
				return;
			}
			if (!done && outcome instanceof UpdateCommit.Blocked blocked) {
				new ReLauncher(UpdateType.UPDATE, null, UpdateRecovery.deferredPopupMessage(blocked.held())).restart(true);
				throw new UpdateDeferredException(deferred.transactionId, blocked.blockedPath(), blocked.message());
			}
		}
		UpdateRecovery.clearDeferredGuard(storage);
		if (deferred.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			clientConfig = ReconfConfigs.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
					.orElseThrow(() -> new ConfigTools.ConfigException("Recovered client config is missing"));
		}
		LOGGER.info("Recovered update transaction {}", deferred.transactionId);
		// The journal is retired, so this relaunch into what the loader should have scanned cannot repeat.
		if (outcome instanceof UpdateCommit.Applied applied && applied.scannedFilesChanged()) {
			LOGGER.info("The recovered update changed files this boot already scanned; restarting");
			new ReLauncher(UpdateType.UPDATE).restart(true);
		}
	}

	/**
	 * A boot that goes on without relaunching after a replay changed what its loader already scanned, so that a replay
	 * that never finishes cannot loop the game: this session runs without those changes, and the log says which.
	 */
	private static void nameUnscannedChanges(UpdateCommit commits) {
		if (!commits.scannedChanges().isEmpty())
			LOGGER.warn("This session runs on what the loader scanned before the replay changed it; the next launch loads: {}", String.join(", ", commits.scannedChanges()));
	}

	private void importBootstrap() {
		BootstrapInstaller.importIfPresent(storage, clientConfig).ifPresent(receipt -> {
			clientConfig = receipt.clientConfig();
			trustedBootstrapApply = receipt.installsModpack() && receipt.hasSecret();
		});
	}
}
