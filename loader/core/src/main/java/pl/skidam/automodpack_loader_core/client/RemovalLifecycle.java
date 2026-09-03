package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.ReviewedUpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.update.UpdateReplanRequiredException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.UpdateTransactionSupport;
import pl.skidam.automodpack_loader_core.client.RestartDecision.ApplyResult;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

/** Owns the player-reviewed removal and deactivation flows plus the post-apply restart decision of one updater session. */
final class RemovalLifecycle {
	private final ClientStorage storage;
	private final ClientUpdatePlanBuilder planBuilder;
	private final Changelogs changelogs;
	private final UpdateLoopDetector updateLoopDetector;
	private final BooleanSupplier fullDownload;
	private ReviewedClientPlan<ClientUpdatePlanBuilder.RemovalPreparation> reviewedRemovalPlan;

	RemovalLifecycle(ClientStorage storage, ClientUpdatePlanBuilder planBuilder, Changelogs changelogs, UpdateLoopDetector updateLoopDetector, BooleanSupplier fullDownload) {
		this.storage = storage;
		this.planBuilder = planBuilder;
		this.changelogs = changelogs;
		this.updateLoopDetector = updateLoopDetector;
		this.fullDownload = fullDownload;
	}

	UpdatePreview previewRemoval() throws Exception {
		return previewRemovalLike(UpdatePreview.Mode.REMOVAL);
	}

	UpdatePreview previewDeactivation() throws Exception {
		return previewRemovalLike(UpdatePreview.Mode.DEACTIVATION);
	}

	private UpdatePreview previewRemovalLike(UpdatePreview.Mode mode) throws Exception {
		ClientUpdatePlanBuilder.RemovalPreparation preparation = planBuilder.prepareRemoval();
		clientConfig = preparation.currentConfig();
		reviewedRemovalPlan = new ReviewedClientPlan<>(preparation, ReviewedUpdatePlan.pending(preparation.plan()));
		return removalPreview(preparation, mode);
	}

	ModpackUpdater.LifecycleApply deactivateModpack() throws Exception {
		return applyRemovalLike(false);
	}

	ModpackUpdater.LifecycleApply removeModpack() throws Exception {
		return applyRemovalLike(true);
	}

	private ModpackUpdater.LifecycleApply applyRemovalLike(boolean remove) throws Exception {
		ReviewedClientPlan<ClientUpdatePlanBuilder.RemovalPreparation> reviewed = reviewedRemovalPlan;
		if (reviewed == null) throw new IllegalStateException("Modpack lifecycle action was not prepared");
		if (!reviewed.review().isApproved()) reviewed.review().approve();
		ClientUpdatePlanBuilder.RemovalPreparation preparation = reviewed.prepared();
		clientConfig = preparation.currentConfig();
		UpdatePreview applied = removalPreview(preparation, remove ? UpdatePreview.Mode.REMOVAL : UpdatePreview.Mode.DEACTIVATION);
		String overlayDigest = storage.overlayDigest(preparation.installed().modpackId);
		UpdateTransaction transaction;
		if (remove)
			transaction = UpdateTransaction.createRemoval(preparation.plan(), ClientPlatform.current(), preparation.expectedPriorIntent(), overlayDigest, preparation.expectedClientConfig());
		else
			transaction = UpdateTransaction.createDeactivation(preparation.plan(), ClientPlatform.current(), preparation.expectedPriorIntent(), overlayDigest, preparation.expectedClientConfig());
		UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor().commit(transaction);
		if (execution.replanRequired()) throw new UpdateReplanRequiredException(execution.blockedPath(), execution.message());
		if (execution.success()) {
			reviewed.review().complete();
			clientConfig = preparation.plannedConfig();
			if (remove) {
				try {
					new ClientGenerationStore(storage).forgetModpack(preparation.installed().modpackId);
				} catch (Exception e) {
					LOGGER.warn("Modpack removal committed, but retained client state cleanup was deferred and can be retried", e);
				}
			}
			changelogs.replaceWith(applied);
			ApplyResult applyResult = RestartDecision.applyResult(preparation.plan());
			changelogs.setRestartReasons(applyResult.reasonDescriptions());
			if (applyResult.requiresRestart()) restartAfterApply(applyResult);
			else updateLoopDetector.clear();
			return new ModpackUpdater.LifecycleApply(true, applyResult.requiresRestart());
		}
		return new ModpackUpdater.LifecycleApply(false, false);
	}

	private UpdatePreview removalPreview(ClientUpdatePlanBuilder.RemovalPreparation preparation, UpdatePreview.Mode mode) throws IOException {
		return UpdatePreview.create(preparation.plan(), removalSelection(preparation), mode).withFeatureManifest(removalManifest(preparation));
	}

	private static ResolvedSelection removalSelection(ClientUpdatePlanBuilder.RemovalPreparation preparation) {
		SelectionIntent intent = preparation.expectedPriorIntent();
		if (intent == null) return null;
		Set<String> selected = preparation.installed().selectedGroups == null ? Set.of() : preparation.installed().selectedGroups;
		Set<String> stale = new TreeSet<>(intent.requestedGroups());
		stale.removeAll(selected);
		return new ResolvedSelection(intent, new TreeSet<>(selected), new TreeSet<>(stale));
	}

	private GroupManifest removalManifest(ClientUpdatePlanBuilder.RemovalPreparation preparation) throws IOException {
		String generationId = preparation.installed().contentToken;
		return new ClientGenerationStore(storage).read(generationId)
				.orElseThrow(() -> new IOException("Installed generation record is unavailable: " + generationId)).manifest();
	}

	void restartAfterApply(ApplyResult applyResult) {
		// Only the launch-time apply hot-loads projection content; any mid-session apply with content changes
		// leaves the running process without them until restart, whether or not the player accepts the restart.
		if (!preload && (!changelogs.changedFiles().isEmpty() || !changelogs.removedFiles().isEmpty())) SessionUpdateState.markAppliedContentNotLoaded();
		if (!applyResult.requiresRestart()) {
			updateLoopDetector.clear();
			// Ask the player to restart instead of silently returning to the game where the next join fails with a mod mismatch.
			if (!preload && (!changelogs.changedFiles().isEmpty() || !changelogs.removedFiles().isEmpty())) {
				LOGGER.info("Update applied with {} changed and {} removed files, but they cannot load into the running game; asking the player to restart", changelogs.changedFiles().size(),
						changelogs.removedFiles().size());
				ScreenManager.restart(fullDownload.getAsBoolean() ? UpdateType.FULL : UpdateType.UPDATE, changelogs);
				return;
			}
			ScreenManager.completeWithoutRestart();
			return;
		}
		String fingerprint = RestartDecision.stateFingerprint(storage, applyResult);
		if (updateLoopDetector.evaluateAndRecord(fingerprint) == UpdateLoopDetector.Decision.SUPPRESS) {
			LOGGER.error("Automatic restart loop detected. AutoModpack already requested two rapid restarts for the same correction state.");
			LOGGER.error("Corrections were applied but still require a restart: {}", String.join(", ", applyResult.reasonDescriptions()));
			LOGGER.error("Another automatic restart was suppressed. The modpack may not be fully active; inspect the surrounding logs and report recurring issues at https://github.com/Skidamek/AutoModpack/issues");
			return;
		}

		new ReLauncher(RestartDecision.applyRestartType(fullDownload.getAsBoolean(), applyResult.restartReasons()), changelogs).restart(false);
	}

	/** Cancels an approved-but-unapplied removal review; the owning updater calls this when it closes. */
	void cancelPendingReview() {
		if (reviewedRemovalPlan != null && reviewedRemovalPlan.review().isApproved()) reviewedRemovalPlan.review().cancel();
	}
}
