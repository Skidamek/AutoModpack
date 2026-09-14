package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.JournalMirror;
import pl.skidam.automodpack_core.update.ReviewedUpdatePlan;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.update.UpdateReplanRequiredException;
import pl.skidam.automodpack_core.update.UpdateReviewPolicy;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;

/**
 * One reviewed update attempt: prepares the plan for its bound target, carries the player's review of it, and commits
 * it exactly once. The session is created after the target is resolved and owns the plan from then on, so the prepared
 * side effects, the review state, and the replan can never drift apart the way separate fields could.
 */
final class UpdateSession implements UpdateAttempt {
	private final ClientStorage storage;
	private final ClientUpdatePlanBuilder planBuilder;
	private final ModpackObjectAcquisition objectAcquisition;
	private final SourceCatalogue sourceCatalogue;
	private final Changelogs changelogs;
	private final ConnectionJsons.ConnectionInfo connectionInfo;
	private final SelectedModpackTarget target;
	private final boolean firstConnection;
	private final Map<String, UpdatePlan.FileState> consentedLocalModFiles;
	private final boolean attaching;
	private ClientUpdatePlanBuilder.PreparedPlan prepared;
	private ReviewedUpdatePlan review;
	private UpdatePlan appliedPlan;

	UpdateSession(ClientStorage storage, ClientUpdatePlanBuilder planBuilder, ModpackObjectAcquisition objectAcquisition, SourceCatalogue sourceCatalogue,
			Changelogs changelogs, ConnectionJsons.ConnectionInfo connectionInfo, SelectedModpackTarget target, boolean firstConnection,
			Map<String, UpdatePlan.FileState> consentedLocalModFiles, boolean attaching) {
		this.storage = Objects.requireNonNull(storage, "storage");
		this.planBuilder = Objects.requireNonNull(planBuilder, "planBuilder");
		this.objectAcquisition = Objects.requireNonNull(objectAcquisition, "objectAcquisition");
		this.sourceCatalogue = Objects.requireNonNull(sourceCatalogue, "sourceCatalogue");
		this.changelogs = changelogs;
		this.connectionInfo = connectionInfo;
		this.target = Objects.requireNonNull(target, "target");
		this.firstConnection = firstConnection;
		this.consentedLocalModFiles = consentedLocalModFiles == null ? Map.of() : consentedLocalModFiles;
		this.attaching = attaching;
	}

	/**
	 * The shared preparation pipeline of every review: acquire the target's mutable objects, reconcile editable state,
	 * and build the plan the player will review. The switch flow prepares the plan objects up front and runs without a
	 * live connection; the flows that need one call the updater's connection guard themselves.
	 */
	void prepare(boolean playerFacing, boolean prepareObjects) throws Exception {
		try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory())) {
			objectAcquisition.acquireTargetObjects(target.flatTarget(), cache, playerFacing);
			// A review presented to the player must not have its unverified verdict flipped by a late platform lookup; the preload path never waits on platform APIs.
			if (playerFacing) sourceCatalogue.awaitSourceLookup();
			planBuilder.reconcileEditableState(cache, target.flatTarget());
			prepared = planBuilder.buildPlan(planInput(true), cache, modCache);
			if (prepareObjects) planBuilder.preparePlanObjects(prepared.plan(), target.flatTarget());
			review = ReviewedUpdatePlan.pending(prepared.plan());
		}
	}

	ClientUpdatePlanBuilder.PreparedPlan prepared() {
		return Objects.requireNonNull(prepared, "The session's plan has not been prepared");
	}

	ReviewedUpdatePlan review() {
		return Objects.requireNonNull(review, "The session's plan has not been prepared");
	}

	@Override
	public boolean isApproved() {
		return review != null && review.isApproved();
	}

	@Override
	public void approve() {
		review().approve();
	}

	/** Cancels a not-yet-executing review; an executing plan is a durable fact and stays that way. */
	@Override
	public void cancel() {
		if (review != null) review.cancel();
	}

	boolean requiresPlayerReview() throws IOException {
		return requiresPlayerReview(prepared(), firstConnection);
	}

	/**
	 * A review is required for first install, a missing projection, or any plan impact. A content-identical
	 * generation advance - only the installed bookmark lags the advertised identity, with zero consequences -
	 * applies silently through the authorized no-op path instead of prompting.
	 */
	private boolean requiresPlayerReview(ClientUpdatePlanBuilder.PreparedPlan prepared, boolean firstInstall) throws IOException {
		if (!firstInstall && !hasPlanImpact(prepared) && storedTarget() != null) return false;
		ModpackJsons.ModpackContentFields installed = storedTarget();
		PackTarget installedTarget = installed == null ? null : PackTarget.fromFlat(installed);
		return UpdateReviewPolicy.requiresPlayerReview(firstInstall, installedTarget, prepared.plan().packTarget(), hasPlanImpact(prepared));
	}

	/** Login reconciliation must also advance a newly advertised generation, even when its files are unchanged. */
	boolean requiresReconciliation(ModpackJsons.ModpackContentFields installed) throws IOException {
		return requiresReconciliation(prepared(), installed);
	}

	private boolean requiresReconciliation(ClientUpdatePlanBuilder.PreparedPlan prepared, ModpackJsons.ModpackContentFields installed) throws IOException {
		PackTarget installedTarget = installed == null ? null : PackTarget.fromFlat(installed);
		return UpdateReviewPolicy.requiresPlayerReview(false, installedTarget, prepared.plan().packTarget(), hasPlanImpact(prepared));
	}

	boolean requiresUpdateBeforeLogin(ModpackUtils.UpdateCheckResult result) throws Exception {
		if (result == null || result.requiresUpdate()) return true;
		if (storage.readActiveState() == null || !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return true;
		try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory())) {
			planBuilder.reconcileEditableState(cache, target.flatTarget());
			ClientUpdatePlanBuilder.PreparedPlan estimate = planBuilder.buildPlan(planInput(false), cache, modCache);
			return requiresReconciliation(estimate, storedTarget());
		}
	}

	boolean hasPlanImpact() throws IOException {
		return hasPlanImpact(prepared());
	}

	private boolean hasPlanImpact(ClientUpdatePlanBuilder.PreparedPlan prepared) throws IOException {
		UpdatePlan plan = prepared.plan();
		return !plan.operations().isEmpty() || !plan.conflicts().isEmpty() || !plan.preservations().isEmpty() || !plan.baselineCaptures().isEmpty()
				|| !plan.restartReasons().isEmpty() || !Objects.equals(plan.plannedClientConfig(), ClientProjectionView.open(storage).logicalConfig(clientConfig));
	}

	/** Which installed state cuts a preview's journal tail: only a matching active bookmark, or the mirror's last entry as the switch flow's fallback. */
	enum InstalledTokenRule {
		ACTIVE_BOOKMARK, ACTIVE_OR_MIRROR_HEAD;

		String installedToken(ClientStorage storage, String modpackId) throws IOException {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state != null && modpackId.equals(state.modpackId)) return state.contentToken;
			return this == ACTIVE_OR_MIRROR_HEAD ? new JournalMirror(storage).lastEntryToken(modpackId).orElse("") : "";
		}
	}

	/** Assembles the player-facing preview of this session's target advance: journal tail, featured notes, and feature manifest. */
	UpdatePreview preview(InstalledTokenRule tokenRule) throws IOException {
		return previewFor(prepared(), tokenRule);
	}

	private UpdatePreview previewFor(ClientUpdatePlanBuilder.PreparedPlan prepared, InstalledTokenRule tokenRule) throws IOException {
		List<JournalEntry> journal = new JournalMirror(storage).entries(target.manifest().modpackId());
		return UpdatePreview.forUpdate(prepared.plan(), target.selection(), journal, tokenRule.installedToken(storage, target.manifest().modpackId()))
				.withFeatureManifest(target.manifest());
	}

	/**
	 * The one commit of the reviewed plan: changelogs, then the transactional commit with its restart decision. An
	 * executing plan is a durable fact, so the commit begins by sealing the review; the executor's own validation and
	 * the outcome-checked replan carry every drift decision from here.
	 */
	@Override
	public RestartDecision.ApplyResult commit() throws Exception {
		recordChangelogs(prepared());
		review().beginExecution();
		AtomicReference<ClientUpdatePlanBuilder.PreparedPlan> applied = new AtomicReference<>(prepared());
		UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor().commitWithReplan(
				() -> commitPlanObjects(applied.get()),
				failedExecution -> {
					ClientUpdatePlanBuilder.PreparedPlan replanned = replanFromMutableInputs(applied.get(), failedExecution);
					applied.set(replanned);
					return commitPlanObjects(replanned);
				});
		if (!execution.success()) {
			if (execution.replanRequired()) throw new UpdateReplanRequiredException(execution.blockedPath(), execution.message());
			throw new UpdateDeferredException(execution.transaction().transactionId, execution.blockedPath(), execution.message());
		}
		review().complete();
		UpdatePlan plan = applied.get().plan();
		this.appliedPlan = plan;
		try {
			cleanupOverlayState(plan, target.manifest().modpackId());
		} catch (IOException e) {
			LOGGER.warn("Modpack update committed, but stale overlay tombstones could not be cleaned", e);
		}
		if (connectionInfo != null && connectionInfo.isComplete()) {
			try {
				ConnectionStore.saveConnection(storage, target.manifest().modpackId(), connectionInfo);
			} catch (IOException e) {
				throw new IOException("Modpack generation committed but connection state could not be saved", e);
			}
		}
		// One of the two attach exits: an explicitly requested sync ends attached at its commit.
		if (attaching) {
			try {
				storage.setDetached(target.manifest().modpackId(), false);
				LOGGER.info("Modpack {} is attached again: the applied sync ends detachment", target.manifest().modpackId());
			} catch (IOException e) {
				throw new IOException("Modpack generation committed but detachment could not be cleared", e);
			}
		}
		return RestartDecision.applyResult(plan);
	}

	/** The plan the commit actually applied, after any replan; the flow applies its config snapshot at completion. */
	UpdatePlan appliedPlan() {
		return Objects.requireNonNull(appliedPlan, "The session has not committed");
	}

	private void recordChangelogs(ClientUpdatePlanBuilder.PreparedPlan prepared) throws IOException {
		UpdatePreview applied = previewFor(prepared, InstalledTokenRule.ACTIVE_BOOKMARK);
		changelogs.replaceWith(applied.withReferences(sourceCatalogue.resolveMainPageReferences(prepared)));
		LOGGER.info("Prepared update changes: {} changed, {} removed", changelogs.changedFiles().size(), changelogs.removedFiles().size());
	}

	private UpdateTransactionExecutor.Execution commitPlanObjects(ClientUpdatePlanBuilder.PreparedPlan prepared) throws IOException {
		planBuilder.preparePlanObjects(prepared.plan(), target.flatTarget());
		return UpdateTransactionSupport.executor().commit(prepared.plan(), target, prepared.overlayDigest(), prepared.expectedClientConfig());
	}

	/** Rebuilds the reviewed plan from the mutable inputs after a replan-required commit, and rechecks it against the player's review. */
	private ClientUpdatePlanBuilder.PreparedPlan replanFromMutableInputs(ClientUpdatePlanBuilder.PreparedPlan prepared,
			UpdateTransactionExecutor.Execution failedExecution) throws IOException {
		ensureSelectedModpackUnchanged(prepared);
		try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory())) {
			planBuilder.reconcileEditableState(cache, target.flatTarget());
			ClientUpdatePlanBuilder.PreparedPlan replanned = planBuilder.buildPlan(planInput(true), cache, modCache);
			try {
				review().requireCompatible(replanned.plan());
			} catch (IllegalStateException e) {
				LOGGER.error("The rebuilt update plan no longer matches the reviewed outcome; the first apply failed with: {}", failedExecution.message(), e);
				throw new UpdateReplanRequiredException(failedExecution.blockedPath(), "Mutable input changed the reviewed update consequences", e);
			}
			recordChangelogs(replanned);
			return replanned;
		}
	}

	private void ensureSelectedModpackUnchanged(ClientUpdatePlanBuilder.PreparedPlan prepared) throws IOException {
		ClientConfigJsons.ClientConfigFieldsV3 current = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
		if (!Objects.equals(current.selectedModpackId, prepared.expectedClientConfig().selectedModpackId))
			throw new UpdateReplanRequiredException(null, "Selected modpack changed while the update was being applied");
	}

	private void cleanupOverlayState(UpdatePlan plan, String modpackId) throws IOException {
		Set<String> deletedPaths = new TreeSet<>(storage.readOverlayState(modpackId).deletedPaths);
		for (UpdatePlan.Operation operation : plan.operations())
			if (operation.root() == UpdatePlan.Root.OVERLAY && operation.operation() == UpdatePlan.OperationType.DELETE)
				deletedPaths.remove(LogicalPath.normalize(operation.relativePath()));
		storage.writeOverlayState(modpackId, deletedPaths);
	}

	private ClientUpdatePlanBuilder.Input planInput(boolean prepareObjects) {
		Map<String, UpdatePlan.FileState> consent = firstConnection ? consentedLocalModFiles : Map.of();
		return new ClientUpdatePlanBuilder.Input(target, connectionInfo, clientConfig, prepareObjects, consent);
	}

	private ModpackJsons.ModpackContentFields storedTarget() throws IOException {
		return ClientProjectionView.open(storage).target();
	}

	/** Rebuilds a pending update from current mutable inputs and commits it when the approved outcome still holds. */
	static UpdateTransactionExecutor.Execution resume(ClientStorage storage, UpdateTransaction pending, ModpackLoaderService modpackLoader, String loaderType) throws Exception {
		ClientUpdatePlanBuilder builder = new ClientUpdatePlanBuilder(storage, modpackLoader, loaderType);
		ClientConfigJsons.ClientConfigFieldsV3 currentConfig = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
		SelectedModpackTarget target = targetFor(storage, pending, currentConfig);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory()); ModFileCache modCache = ModFileCache.open(storage.modCacheDirectory())) {
			builder.reconcileEditableState(cache, target.flatTarget());
			ClientUpdatePlanBuilder.PreparedPlan prepared = builder.buildPlan(new ClientUpdatePlanBuilder.Input(target, null, currentConfig, true), cache, modCache);
			if (!ReviewedUpdatePlan.outcomeCompatible(pending.plan(), prepared.plan()))
				throw new UpdateReplanRequiredException(null, "Mutable inputs changed the pending update outcome; a new review is required");
			builder.preparePlanObjects(prepared.plan(), target.flatTarget());
			return UpdateTransactionSupport.executor().commit(prepared.plan(), target, prepared.overlayDigest(), prepared.expectedClientConfig());
		}
	}

	private static SelectedModpackTarget targetFor(ClientStorage storage, UpdateTransaction pending, ClientConfigJsons.ClientConfigFieldsV3 currentConfig) throws IOException {
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		PackDocument pendingDocument = generations.document(pending);
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		boolean configStillDescribesThePendingInput = active == null
				? !currentConfig.hasSelectedModpack()
				: Objects.equals(currentConfig.selectedModpackId, active.modpackId);
		PackDocument record;
		if (configStillDescribesThePendingInput || pending.plan().modpackId().equals(currentConfig.selectedModpackId))
			record = newer(pendingDocument, newest(generations, pending.plan().modpackId()));
		else {
			if (!ModpackId.isValid(currentConfig.selectedModpackId))
				throw new IOException("Selected modpack changed to an invalid or empty ID while replanning the pending update");
			record = newest(generations, currentConfig.selectedModpackId);
			if (record == null) throw new IOException("Selected modpack generation is not installed: " + currentConfig.selectedModpackId);
		}
		ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
		SelectionIntent storedIntent = selections.get(record.manifest().modpackId()).orElse(null);
		if (record.manifest().modpackId().equals(pending.plan().modpackId()) && Objects.equals(storedIntent, pending.expectedPriorIntent()))
			return SelectedModpackTarget.prepare(record, storedIntent, pending.targetIntent(), pending.platform());
		if (storedIntent == null) return SelectedModpackTarget.prepareDefault(record, pending.platform());
		return SelectedModpackTarget.prepare(record, storedIntent, storedIntent, pending.platform());
	}

	private static PackDocument newest(ClientGenerationStore generations, String modpackId) throws IOException {
		if (!ModpackId.isValid(modpackId)) return null;
		return generations.newestDocument(modpackId);
	}

	private static PackDocument newer(PackDocument first, PackDocument second) {
		if (second == null) return first;
		if (first == null) return second;
		return Comparator.comparing(PackDocument::createdAt).thenComparing(PackDocument::contentToken).compare(first, second) >= 0 ? first : second;
	}
}
