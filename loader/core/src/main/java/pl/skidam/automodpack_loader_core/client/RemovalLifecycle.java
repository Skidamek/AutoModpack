package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

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
import pl.skidam.automodpack_loader_core.UpdateTransactionSupport;
import pl.skidam.automodpack_loader_core.client.RestartDecision.ApplyResult;

/** Owns one player-reviewed removal or deactivation attempt: prepare, review, commit. Restart is the updater's. */
final class RemovalLifecycle {
	private final ClientStorage storage;
	private final ClientUpdatePlanBuilder planBuilder;
	private final Changelogs changelogs;
	private final Consumer<ApplyResult> afterApply;
	private ClientUpdatePlanBuilder.RemovalPreparation prepared;
	private ReviewedUpdatePlan review;

	RemovalLifecycle(ClientStorage storage, ClientUpdatePlanBuilder planBuilder, Changelogs changelogs, Consumer<ApplyResult> afterApply) {
		this.storage = storage;
		this.planBuilder = planBuilder;
		this.changelogs = changelogs;
		this.afterApply = afterApply;
	}

	UpdatePreview previewRemoval() throws Exception {
		return previewRemovalLike(UpdatePreview.Mode.REMOVAL);
	}

	UpdatePreview previewDeactivation() throws Exception {
		return previewRemovalLike(UpdatePreview.Mode.DEACTIVATION);
	}

	private UpdatePreview previewRemovalLike(UpdatePreview.Mode mode) throws Exception {
		prepared = planBuilder.prepareRemoval();
		clientConfig = prepared.currentConfig();
		review = ReviewedUpdatePlan.pending(prepared.plan());
		return removalPreview(prepared, mode);
	}

	ModpackUpdater.LifecycleApply deactivateModpack() throws Exception {
		return applyRemovalLike(false);
	}

	ModpackUpdater.LifecycleApply removeModpack() throws Exception {
		return applyRemovalLike(true);
	}

	private ModpackUpdater.LifecycleApply applyRemovalLike(boolean remove) throws Exception {
		if (prepared == null || review == null) throw new IllegalStateException("Modpack lifecycle action was not prepared");
		if (!review.isApproved()) review.approve();
		clientConfig = prepared.currentConfig();
		UpdatePreview applied = removalPreview(prepared, remove ? UpdatePreview.Mode.REMOVAL : UpdatePreview.Mode.DEACTIVATION);
		String overlayDigest = storage.overlayDigest(prepared.installed().modpackId);
		UpdateTransaction transaction;
		if (remove)
			transaction = UpdateTransaction.createRemoval(prepared.plan(), ClientPlatform.current(), prepared.expectedPriorIntent(), prepared.installed().ownershipLedger, overlayDigest,
					prepared.expectedClientConfig());
		else
			transaction = UpdateTransaction.createDeactivation(prepared.plan(), ClientPlatform.current(), prepared.expectedPriorIntent(), prepared.installed().ownershipLedger, overlayDigest,
					prepared.expectedClientConfig());
		UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor().commit(transaction);
		if (execution.replanRequired()) throw new UpdateReplanRequiredException(execution.blockedPath(), execution.message());
		if (execution.success()) {
			review.complete();
			clientConfig = prepared.plannedConfig();
			if (remove) {
				try {
					new ClientGenerationStore(storage).forgetModpack(prepared.installed().modpackId);
				} catch (Exception e) {
					LOGGER.warn("Modpack removal committed, but retained client state cleanup was deferred and can be retried", e);
				}
			}
			changelogs.replaceWith(applied);
			ApplyResult applyResult = RestartDecision.applyResult(prepared.plan());
			changelogs.setRestartReasons(applyResult.reasonIds());
			afterApply.accept(applyResult);
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
		return new ClientGenerationStore(storage).policyDocument(preparation.installed().policySha1);
	}

	/** Cancels an approved-but-unapplied removal review; the owning updater calls this when it closes. */
	void cancelPendingReview() {
		if (review != null && review.isApproved()) review.cancel();
	}
}
