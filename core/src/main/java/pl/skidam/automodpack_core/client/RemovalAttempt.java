package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.client.RestartDecision.ApplyResult;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
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

/** One player-reviewed removal or deactivation: prepare, review, commit. Restart is the updater's. */
final class RemovalAttempt implements UpdateAttempt {
	enum Kind {
		REMOVAL, DEACTIVATION
	}

	private final ClientStorage storage;
	private final ClientUpdatePlanBuilder planBuilder;
	private final Changelogs changelogs;
	private final Kind kind;
	private ClientUpdatePlanBuilder.RemovalPreparation prepared;
	private ReviewedUpdatePlan review;

	RemovalAttempt(ClientStorage storage, ClientUpdatePlanBuilder planBuilder, Changelogs changelogs, Kind kind) {
		this.storage = Objects.requireNonNull(storage, "storage");
		this.planBuilder = Objects.requireNonNull(planBuilder, "planBuilder");
		this.changelogs = changelogs;
		this.kind = Objects.requireNonNull(kind, "kind");
	}

	Kind kind() {
		return kind;
	}

	/** The config snapshot the removal plans to persist; the flow applies it at completion. */
	ClientConfigJsons.ClientConfigFieldsV3 plannedConfig() {
		return Objects.requireNonNull(prepared, "Modpack lifecycle action was not prepared").plannedConfig();
	}

	UpdatePreview preview() throws Exception {
		prepared = planBuilder.prepareRemoval();
		review = ReviewedUpdatePlan.pending(prepared.plan());
		return removalPreview(prepared, kind == Kind.REMOVAL ? UpdatePreview.Mode.REMOVAL : UpdatePreview.Mode.DEACTIVATION);
	}

	@Override
	public boolean isApproved() {
		return review != null && review.isApproved();
	}

	@Override
	public void approve() {
		if (review == null) throw new IllegalStateException("Modpack lifecycle action was not prepared");
		review.approve();
	}

	@Override
	public void cancel() {
		if (review != null) review.cancel();
	}

	@Override
	public ApplyResult commit() throws Exception {
		if (prepared == null || review == null) throw new IllegalStateException("Modpack lifecycle action was not prepared");
		review.beginExecution();
		boolean remove = kind == Kind.REMOVAL;
		UpdatePreview applied = removalPreview(prepared, remove ? UpdatePreview.Mode.REMOVAL : UpdatePreview.Mode.DEACTIVATION);
		UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor().commit(transactionOf(prepared, kind, storage.overlayDigest(prepared.installed().modpackId)));
		if (execution.replanRequired()) throw new UpdateReplanRequiredException(execution.blockedPath(), execution.message());
		if (!execution.success()) throw new IOException(remove ? "Modpack removal did not complete" : "Modpack deactivation did not complete");
		review.complete();
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
		return applyResult;
	}

	static UpdateTransactionExecutor.Execution resume(ClientStorage storage, UpdateTransaction pending, ModpackLoaderService modpackLoader, String loaderType) throws Exception {
		ClientUpdatePlanBuilder builder = new ClientUpdatePlanBuilder(storage, modpackLoader, loaderType);
		ClientUpdatePlanBuilder.RemovalPreparation preparation = builder.prepareRemoval();
		Kind kind = pending.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL ? Kind.REMOVAL : Kind.DEACTIVATION;
		UpdateTransaction transaction = transactionOf(preparation, kind, storage.overlayDigest(preparation.installed().modpackId));
		if (!ReviewedUpdatePlan.outcomeCompatible(pending.plan(), preparation.plan()))
			throw new UpdateReplanRequiredException(null, "Mutable inputs changed the pending removal outcome; a new review is required");
		return UpdateTransactionSupport.executor().commit(transaction);
	}

	private static UpdateTransaction transactionOf(ClientUpdatePlanBuilder.RemovalPreparation preparation, Kind kind, String overlayDigest) {
		if (kind == Kind.REMOVAL)
			return UpdateTransaction.createRemoval(preparation.plan(), ClientPlatform.current(), preparation.expectedPriorIntent(), preparation.installed().ownershipLedger, overlayDigest,
					preparation.expectedClientConfig());
		return UpdateTransaction.createDeactivation(preparation.plan(), ClientPlatform.current(), preparation.expectedPriorIntent(), preparation.installed().ownershipLedger, overlayDigest,
				preparation.expectedClientConfig());
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
}
