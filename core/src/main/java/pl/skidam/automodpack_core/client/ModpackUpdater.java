package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.client.RestartDecision.ApplyResult;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.PreviewPayload;
import pl.skidam.automodpack_core.screen.ReviewActions;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.screen.SourceCounts;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStateJournal;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.RestartDemand;
import pl.skidam.automodpack_core.update.RestartPolicy;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;

/**
 * The update engine facade: the one construction point for storage, loaders, the transfer client, and the review, and
 * the driver of the sync, switch, and lifecycle flows. The player-facing review lives in {@link ReviewSession}; the
 * apply machinery and every restart decision stay here.
 */
public class ModpackUpdater implements AutoCloseable {
	/** The update engine's background pool: one daemon-thread pool for flow, review, and controller dispatch on the client; the transport keeps its own. */
	private static final ExecutorService APP_EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "automodpack-update");
		t.setDaemon(true);
		return t;
	});

	/** The app's client-side background pool. */
	public static ExecutorService executor() {
		return APP_EXECUTOR;
	}

	private Changelogs changelogs = new Changelogs();
	boolean fullDownload = false;
	private SelectedModpackTarget selectedTarget;
	private ModpackJsons.ModpackContentFields serverModpackContent;
	private final ConnectionJsons.ConnectionInfo connectionInfo;
	private final DownloadClient downloadClient;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final UpdateLoopDetector updateLoopDetector;
	private final ClientStorage storage;
	private final PlatformCache platformCache;
	private final ClientUpdatePlanBuilder planBuilder;
	private final SourceCatalogue sourceCatalogue;
	private final ProjectionLoader projectionLoader;
	private final ModpackObjectAcquisition objectAcquisition;
	private final AtomicReference<UpdateAttempt> attempt = new AtomicReference<>();
	private final ReviewSession review;
	private final LifecycleFlow lifecycle;
	/**
	 * The attaching intent of an explicitly requested sync. Detachment ends only through this intent: an applied plan
	 * clears the flag inside the commit, and a requested sync that finds nothing to apply clears it on its early exit.
	 * Detachment is declared by explicit entry points; nothing else ever clears it.
	 */
	private boolean attaching;

	private String getModpackName() {
		return serverModpackContent.modpackName;
	}

	SelectedModpackTarget getSelectedTarget() {
		return Objects.requireNonNull(selectedTarget, "Selected modpack target is unavailable");
	}

	/** The selected target's objects the local store still misses, after counting the sources already available locally. */
	private Set<ModpackJsons.ModpackContentFields.ModpackContentItem> missingSelectedTargetObjects() throws IOException {
		if (selectedTarget == null || serverModpackContent == null) throw new IOException("Selected modpack target is unavailable");
		try (var cache = FileCache.open(storage.fileCacheDirectory())) {
			planBuilder.populateStoreFromCachedLocations(selectedTarget.flatTarget(), cache);
			return objectAcquisition.missingTargetObjects(selectedTarget.flatTarget(), cache);
		}
	}

	/** Returns whether the selected installed target needs an authenticated object-transfer session. */
	public boolean requiresSelectedTargetDownload() throws IOException {
		return !missingSelectedTargetObjects().isEmpty();
	}

	/** The selected target's download cost with the local store: the bytes of its objects not already acquired. */
	long uncachedSelectedTargetBytes() throws IOException {
		long bytes = 0;
		for (var item : missingSelectedTargetObjects()) bytes += item.size;
		return bytes;
	}

	ConnectionJsons.ConnectionInfo connectionInfo() {
		return connectionInfo;
	}

	ModpackJsons.ModpackContentFields storedTarget() throws IOException {
		return ClientProjectionView.open(storage).target();
	}

	/** Applies a new group selection to the installed target on an explicit review reselect. */
	void reselectTarget(SelectionIntent intent) {
		Objects.requireNonNull(intent, "intent");
		SelectedModpackTarget current = getSelectedTarget();
		SelectedModpackTarget replacement = SelectedModpackTarget.prepare(current.document(), current.expectedPriorIntent(), intent, current.platform());
		selectedTarget = replacement;
		serverModpackContent = replacement.flatTarget();
	}

	/** The actions and live polls a review-backed screen may drive; the seam type, backed by this engine's review. */
	ReviewActions reviewActions() {
		return review.reviewActions();
	}

	/** Selected jar paths of the selected target without a Modrinth/CurseForge hash hit. */
	List<String> unverifiedSelectedJarPaths() {
		return review.unverifiedSelectedJarPaths();
	}

	/** Jar counts by lookup source over the selected target; the lookup settles before review screens open. */
	public SourceCounts selectedJarSourceCounts() {
		return sourceCatalogue.selectedJarSourceCounts(getSelectedTarget());
	}

	/** Minecraft join target as `host:port`, or "" when this engine has no live connection. */
	public String joinOrigin() {
		return review.joinOrigin();
	}

	/** True when the plan would write a gated jar that has no first-party hit. */
	boolean planWritesUnverifiedJar(UpdatePlan plan) {
		return review.planWritesUnverifiedJar(plan);
	}

	/** Stops the in-flight update work after the player backed out of the preparing screen. */
	public void cancelFromPlayer() {
		review.cancelFromPlayer();
	}

	/** Builds a reviewable switch plan for an installed generation, acquiring selected objects when necessary. */
	UpdatePreview previewInstalledSwitch() throws Exception {
		if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Installed modpack target is unavailable");
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		boolean projectionPresent = active != null && Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS);
		// SelectionIntent equality ignores the platform override on purpose, so the platform the active projection was
		// built for is compared separately: a platform-only rechoice changes the projection and must switch, not throw.
		if (projectionPresent && selectedTarget.manifest().modpackId().equals(active.modpackId)
				&& selectedTarget.document().contentToken().equals(active.contentToken)
				&& Objects.equals(selectedTarget.expectedPriorIntent(), selectedTarget.selection().intent())
				&& Objects.equals(selectedTarget.platform(), ClientPlatform.effective(selectedTarget.expectedPriorIntent())))
			throw new IllegalArgumentException("Installed modpack target generation, group selection, and platform are already active");
		UpdateSession switchAttempt = beginUpdateAttempt();
		switchAttempt.prepare(true, true);
		return switchAttempt.preview(UpdateSession.InstalledTokenRule.ACTIVE_OR_MIRROR_HEAD);
	}

	/** Applies the last installed-generation switch plan through the normal atomic transaction executor. */
	void applyInstalledSwitch() throws Exception {
		UpdateAttempt current = attempt.get();
		if (!(current instanceof UpdateSession switchPlan) || selectedTarget == null) throw new IllegalStateException("Installed modpack switch was not prepared");
		// The confirm click that reached this action is the review's consent; commit itself refuses an unapproved plan.
		switchPlan.approve();
		// The switch flow reports its failure through its own caller, so its failure handling carries the failure out of the harness.
		AtomicReference<Exception> propagated = new AtomicReference<>();
		runReviewedFlow(new ApplyFlow("Installed modpack switch", () -> new ReLauncher(UpdateType.SELECT, changelogs).restart(false), propagated::set, this::close),
				() -> restartAfterApply(commitFlow(switchPlan)));
		if (propagated.get() != null) throw propagated.get();
	}

	/**
	 * Applies the last prepared switch as a rollback to an older generation: detachment is declared before the commit so
	 * the published active state keeps the flag, making the rollback itself the declaration of local sovereignty.
	 */
	void applyGenerationRollback() throws Exception {
		if (selectedTarget == null) throw new IllegalStateException("Generation rollback was not prepared");
		UpdateAttempt current = attempt.get();
		if (!(current instanceof UpdateSession session)) throw new IllegalStateException("Installed modpack switch was not prepared");
		new ClientGenerationStore(storage).declareDetached(selectedTarget.manifest().modpackId());
		session.declareStateKind(ClientStateJournal.Kind.ROLLBACK.name());
		applyInstalledSwitch();
	}

	public ModpackUpdater(SelectedModpackTarget selectedTarget, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret, ClientStorage storage) {
		this(selectedTarget, connectionInfo, secret, storage, null);
	}

	public ModpackUpdater(ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret, ClientStorage storage) {
		this(null, connectionInfo, secret, storage, null);
	}

	public ModpackUpdater(SelectedModpackTarget selectedTarget, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret, ClientStorage storage,
			DownloadClient downloadClient) {
		this.selectedTarget = selectedTarget;
		this.serverModpackContent = selectedTarget == null ? null : selectedTarget.flatTarget();
		this.connectionInfo = connectionInfo;
		this.storage = Objects.requireNonNull(storage, "storage");
		this.platformCache = openPlatformCache(storage);
		this.planBuilder = new ClientUpdatePlanBuilder(this.storage, MODPACK_LOADER, LOADER);
		this.updateLoopDetector = new UpdateLoopDetector(storage.restartLoopStateFile());
		this.sourceCatalogue = new SourceCatalogue(() -> selectedTarget, this.platformCache);
		this.projectionLoader = new ProjectionLoader(this.storage, this::storedTarget);
		this.downloadClient = downloadClient;
		AtomicBoolean playerCancelled = new AtomicBoolean();
		this.objectAcquisition = new ModpackObjectAcquisition(this.storage, this.platformCache, this.sourceCatalogue, this.planBuilder, this.connectionInfo, this.downloadClient,
				playerCancelled, this::getModpackName, this::cancelFromPlayer);
		this.review = new ReviewSession(this, this.storage, this.sourceCatalogue, playerCancelled);
		this.lifecycle = new LifecycleFlow(this, this.storage, this.planBuilder, this.changelogs);
	}

	/** A session for one update attempt against the currently selected target, carrying this attempt's consent and attach intent. */
	private UpdateSession newSession() {
		return new UpdateSession(storage, planBuilder, objectAcquisition, sourceCatalogue, changelogs, connectionInfo, getSelectedTarget(), review.firstConnection(),
				review.consentedLocalModFiles(), attaching);
	}

	/** Replaces any in-flight attempt so prepare, review, and commit cannot drift across two sessions. */
	<T extends UpdateAttempt> T beginAttempt(T next) {
		UpdateAttempt previous = attempt.getAndSet(next);
		if (previous != null) previous.cancel();
		return next;
	}

	UpdateSession beginUpdateAttempt() {
		return beginAttempt(newSession());
	}

	RemovalAttempt requireRemoval(RemovalAttempt.Kind kind) {
		UpdateAttempt current = attempt.get();
		if (!(current instanceof RemovalAttempt removal) || removal.kind() != kind) throw new IllegalStateException("Modpack lifecycle action was not prepared");
		return removal;
	}

	private static PlatformCache openPlatformCache(ClientStorage storage) {
		try {
			return PlatformCache.open(storage.platformCacheDirectory());
		} catch (IOException e) {
			throw new IllegalStateException("Cannot open platform cache for " + storage.gameDirectory(), e);
		}
	}

	/** Trusted bootstrap install: apply the selected pack on this launch without a review screen. */
	public void applyTrustedInstall() {
		applySelectedTargetWithoutReview(true);
	}

	/**
	 * Explicit attach of a detached pack: the player asked to sync to the server head, so the normal reviewed update
	 * runs and its commit dissolves the detachment. A declined or failed attach leaves the flag untouched.
	 */
	public void attachAndSync() {
		requestAttach();
		processModpackUpdate(true);
	}

	/** Declares the attaching intent of an explicitly requested sync; an attached pack simply stays attached. */
	public void requestAttach() {
		attaching = true;
	}

	/**
	 * Ends an explicitly requested sync that found nothing to apply; the commit is the sync's other exit. The clear is
	 * a no-op for a pack that was never detached, so every explicitly requested sync ends attached without branching.
	 */
	public void finishAttachWithoutChanges() throws IOException {
		if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Selected modpack target is unavailable");
		storage.setDetached(selectedTarget.manifest().modpackId(), false);
		LOGGER.info("Modpack {} is attached again: syncing to the server's current generation", selectedTarget.manifest().modpackId());
	}

	/** How an update attempt ended: a player-facing review took over the screen, the update applied inline, or nothing was applied. */
	public enum UpdateOutcome {
		/** A first-install welcome or a review preview took the screen; completion belongs to that flow now. */
		REVIEW_OPENED,
		/** The update applied inline; nothing is pending and the pack is current. */
		APPLIED,
		/** Cancelled by the player, deferred to a restart, or failed; the wait episode is settled. */
		INCOMPLETE
	}

	/**
	 * When {@code showWaitingScreen} is false a player-facing screen already owns the wait and shows its own busy state.
	 * Returns {@link UpdateOutcome#APPLIED} exactly when the update ran inline to completion; {@link
	 * UpdateOutcome#REVIEW_OPENED} when a player-facing flow took over (first-install welcome, or a review preview
	 * accepted for display); {@link UpdateOutcome#INCOMPLETE} when the flow was cancelled, deferred to a restart, or
	 * failed. A wait episode never outlives this call: a successor screen or {@link ScreenManager#restore()} settled it.
	 */
	public UpdateOutcome processModpackUpdate(boolean showWaitingScreen) {
		if (preload) {
			applySelectedTargetWithoutReview(false);
			return UpdateOutcome.APPLIED;
		}

		try {
			requireLiveConnection();
			if (showWaitingScreen) ScreenManager.waiting(review::cancelFromPlayer);

			if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Selected modpack target is unavailable");

			// Handle a modpack installed for the first time: the local-mod consent and group defaults only apply here
			if (!new ClientGenerationStore(storage).hasLocalState(selectedTarget.manifest().modpackId())) {
				review.beginFirstInstallReview();
				return UpdateOutcome.REVIEW_OPENED;
			} else if (storage.readActiveState() == null || !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) {
				// Handle an installed modpack without an active projection: reactivate it through the reviewed switch plan
				return startInstalledSwitch(showWaitingScreen);
			} else {
				ModpackUtils.reprotectActiveFiles(serverModpackContent, storage);

				return review.startUpdate(showWaitingScreen);
			}
		} catch (UpdateDeferredException e) {
			close();
			if (review.isCancelledByPlayer()) return UpdateOutcome.INCOMPLETE;
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			deferToHelper();
			new ReLauncher(UpdateType.UPDATE, changelogs).restart(preload);
			return UpdateOutcome.INCOMPLETE;
		} catch (Exception e) {
			close();
			if (review.abortedByPlayer(e)) return UpdateOutcome.INCOMPLETE;
			showUpdateFailure(e);
			return UpdateOutcome.INCOMPLETE;
		}
	}

	/**
	 * Applies the selected pack during launch. Preload has no review screen. The plan's restart reasons are the
	 * authority: copies or deletes in the standard mods folder, a loader-version swap, and the other restart reasons
	 * stop this process so the next launch sees the real {@code mods/} tree. Projection-only work loads in this process.
	 * A deferred transaction restarts for the detached helper. First install waits for in-game review unless
	 * {@code applyFirstInstall} is set (trusted bootstrap).
	 */
	private void applySelectedTargetWithoutReview(boolean applyFirstInstall) {
		runReviewedFlow(new ApplyFlow("Launch apply", () -> new ReLauncher(UpdateType.UPDATE, changelogs).restart(true), e -> {
			LOGGER.error("Failed to apply the selected modpack; no projection changes were made outside the existing transaction guarantees", e);
			if (!preload && !review.abortedByPlayer(e)) showUpdateFailure(e);
		}, this::closeLaunchApply), () -> launchApply(applyFirstInstall));
	}

	/** The launch apply's own steps: resolve the target, prepare without a preview, and commit the approved plan. */
	private void launchApply(boolean applyFirstInstall) throws Exception {
		if (selectedTarget == null || serverModpackContent == null) {
			LOGGER.info("Skipping launch apply because no resolved target is available");
			return;
		}
		requireLiveConnection();
		review.firstConnection(!new ClientGenerationStore(storage).hasLocalState(selectedTarget.manifest().modpackId()));
		review.resetLocalModConsent();
		if (review.firstConnection() && !applyFirstInstall) {
			try (var cache = FileCache.open(storage.fileCacheDirectory())) {
				objectAcquisition.acquireTargetObjects(selectedTarget.flatTarget(), cache, false);
			}
			LOGGER.info("Launch apply is waiting for first-install review");
			return;
		}
		long start = System.currentTimeMillis();
		sourceCatalogue.startSourceFetch();
		UpdateSession launch = beginUpdateAttempt();
		launch.prepare(false, false);
		if (review.planWritesUnverifiedJar(launch.prepared().plan())) {
			LOGGER.warn("Launch apply aborted: unverified jars will not be written during preload; leaving the live pack unchanged");
			return;
		}
		if (!review.firstConnection() && !launch.requiresReconciliation(storedTarget())) {
			LOGGER.info("Launch apply reused the active projection");
			return;
		}
		launch.approve();
		ApplyResult applyResult = commitFlow(launch);
		LOGGER.info("Launch apply completed; restart demand: {} Took: {}ms", RestartPolicy.atPreload(applyResult.restartReasons()), System.currentTimeMillis() - start);
		finishLaunchApply(applyResult);
	}

	/** Launch apply's close handling: hot-load the active projection when preloading, then close. */
	private void closeLaunchApply() {
		try {
			if (preload) projectionLoader.loadSelectedActiveProjection();
		} catch (Exception e) {
			LOGGER.error("Failed to load the active modpack projection after launch apply", e);
		}
		close();
	}

	private void finishLaunchApply(ApplyResult applyResult) throws IOException {
		if (!preload) {
			restartAfterApply(applyResult);
			return;
		}
		if (RestartPolicy.atPreload(applyResult.restartReasons()) != RestartDemand.REQUIRED) {
			LOGGER.info("Launch apply needs no restart at preload; hot-loading the fresh pack in this boot");
			return;
		}
		String fingerprint = RestartDecision.stateFingerprint(storage, applyResult);
		if (updateLoopDetector.evaluateAndRecord(fingerprint).decision() == UpdateLoopDetector.Decision.SUPPRESS) {
			LOGGER.error("Automatic restart loop detected. AutoModpack already requested two rapid restarts for the same correction state.");
			LOGGER.error("Corrections were applied but still require a restart: {}", String.join(", ", applyResult.reasonDescriptions()));
			LOGGER.error("Another automatic restart was suppressed. The modpack may not be fully active; inspect the surrounding logs and report recurring issues at https://github.com/Skidamek/AutoModpack/issues");
			return;
		}
		new ReLauncher(RestartDecision.launchRestartType(review.firstConnection(), applyResult.restartReasons()), changelogs).restart(true);
	}

	public void loadModpack() throws Exception {
		projectionLoader.loadModpack();
	}

	public boolean requiresUpdateBeforeLogin(ModpackUtils.UpdateCheckResult result) throws Exception {
		if (result == null || result.requiresUpdate()) return true;
		if (storage.readActiveState() == null || !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return true;
		if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Selected modpack target is unavailable");
		return newSession().requiresUpdateBeforeLogin(result);
	}

	/** Removal and deactivation of the installed modpack: the lifecycle flow owns the dance, the facade is its construction point. */
	public void removeOrDeactivate(boolean deactivation, String modpackName, Runnable released, Runnable removed) {
		lifecycle.removeOrDeactivate(deactivation, modpackName, released, removed);
	}

	/**
	 * The one flow completion: the commit lands the attempt's planned config document on the engine, the same for every
	 * flow. The write lock spans the commit too, because the transaction's durable config write is part of the same
	 * critical section as the landing - a concurrent preference save must wait for it rather than interleave.
	 */
	ApplyResult commitFlow(UpdateAttempt attempt) throws Exception {
		ApplyResult result;
		synchronized (clientConfigWriteLock) {
			result = attempt.commit();
			clientConfig = attempt.plannedClientConfig();
		}
		return result;
	}

	/** The pending restart demand is satisfied: no restart was asked for, so the loop detector forgets the history. */
	void clearUpdateLoopDetector() {
		updateLoopDetector.clear();
	}

	/**
	 * Post-apply restart for a running game. {@link RestartPolicy#inGame} chooses required, offered, or none.
	 * Required and offered both open the restart screen with a way back; none returns to the multiplayer hub.
	 * File-changing applies mark content as not loaded so a failed join before a world exists can nudge a restart.
	 */
	void restartAfterApply(ApplyResult applyResult) {
		Set<String> paths = changelogs.changedOrRemovedPaths();
		if (!paths.isEmpty()) SessionUpdateState.markAppliedContentNotLoaded();
		RestartDemand demand = RestartPolicy.inGame(applyResult.restartReasons(), paths);
		if (demand == RestartDemand.NONE) {
			updateLoopDetector.clear();
			ScreenManager.completeWithoutRestart();
			return;
		}
		LOGGER.info("Update applied with in-game restart demand {}; asking the player to restart", demand);
		ScreenManager.restart(RestartDecision.applyRestartType(fullDownload, applyResult.restartReasons()), changelogs);
	}

	/**
	 * Presents the switch plan for an installed modpack that has no active projection, instead of replaying the
	 * first-install flow. Returns true only when the preview was accepted for display.
	 */
	private UpdateOutcome startInstalledSwitch(boolean showWaitingScreen) {
		try {
			requireLiveConnection();
			if (showWaitingScreen) ScreenManager.waiting(review::cancelFromPlayer);
			UpdatePreview preview = previewInstalledSwitch();
			if (review.isCancelledByPlayer()) {
				close();
				return UpdateOutcome.INCOMPLETE;
			}
			UpdateAttempt switchAttempt = attempt.get();
			Runnable continueAction = () -> {
				try {
					if (attempt.get() != switchAttempt) return;
					applyInstalledSwitch();
				} catch (Exception e) {
					if (!review.abortedByPlayer(e)) showUpdateFailure(e);
				}
			};
			if (!ScreenManager.preview(previewPayload(preview, (Runnable) () -> executor().execute(continueAction), this::close))) {
				LOGGER.warn("Installed modpack switch preview could not be shown; leaving the client without an active modpack");
				close();
				return UpdateOutcome.INCOMPLETE;
			}
			return UpdateOutcome.REVIEW_OPENED;
		} catch (Exception e) {
			if (!review.abortedByPlayer(e)) showUpdateFailure(e);
			close();
			return UpdateOutcome.INCOMPLETE;
		}
	}

	/** Runs the reviewed plan's commit through the shared apply harness; the review drives it, the engine owns the tails. */
	ModpackUpdater.ApplyStatus applyApprovedPlan(UpdateSession reviewed, long start) {
		if (review.isCancelledByPlayer()) {
			close();
			return ApplyStatus.FAILED;
		}
		return runReviewedFlow(new ApplyFlow("Update", () -> {
			if (!review.isCancelledByPlayer()) new ReLauncher(UpdateType.UPDATE, changelogs).restart(preload);
		}, e -> {
			if (review.abortedByPlayer(e)) LOGGER.info("Modpack update apply was aborted by the player");
			else showUpdateFailure(e);
		}, this::close), () -> {
			ApplyResult applyResult = commitFlow(reviewed);
			LOGGER.info("Update completed! Restart demand: {} Took: {}ms", RestartPolicy.inGame(applyResult.restartReasons(), changelogs.changedOrRemovedPaths()),
					System.currentTimeMillis() - start);
			restartAfterApply(applyResult);
		});
	}

	void requireLiveConnection() throws IOException {
		if (connectionInfo == null || !connectionInfo.isComplete()) throw new IOException("Modpack connection is unavailable");
		objectAcquisition.requireTransferSession();
	}

	/**
	 * The reviewed apply harness owning the shared tails of every flow once: a deferred transaction warns with the
	 * flow's name and takes the flow's deferred restart, a failure goes to the flow's failure handling, and every
	 * exit closes through the flow's close handling. The body is the flow's own steps; the harness absorbs none of
	 * its decisions.
	 */
	private ApplyStatus runReviewedFlow(ApplyFlow flow, FlowBody body) {
		try {
			body.run();
			return ApplyStatus.APPLIED;
		} catch (UpdateDeferredException e) {
			LOGGER.warn("{} transaction {} is waiting for the detached helper to release {}", flow.name(), e.getTransactionId(), e.getBlockedPath());
			deferToHelper();
			flow.deferredRestart().run();
			return ApplyStatus.DEFERRED;
		} catch (Exception e) {
			flow.failed().accept(e);
			return ApplyStatus.FAILED;
		} finally {
			flow.closed().run();
		}
	}

	/** The failure tail every flow shares: the player-facing update failure on the current screen. */
	static void showUpdateFailure(Exception e) {
		ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
	}

	/**
	 * Launches a fresh helper for the pending transaction before the flow's deferred restart hands recovery to it and
	 * the next boot. A launch failure never cancels the restart: the transaction stays pending either way, and the next
	 * launch's recovery is the designed fallback.
	 */
	private static void deferToHelper() {
		try {
			DetachedUpdateHelper.launch();
		} catch (IOException launchFailure) {
			LOGGER.error("Could not launch the detached update helper", launchFailure);
		}
	}

	/** Wraps a reviewable plan with this engine's review backing; the unverified-jar gate is precomputed, mode-gated. */
	PreviewPayload previewPayload(UpdatePreview preview, Runnable continueAction, Runnable cancelAction) {
		boolean writesUnverifiedJar = (preview.mode() == UpdatePreview.Mode.UPDATE || preview.mode() == UpdatePreview.Mode.ROLLBACK) && review.planWritesUnverifiedJar(preview.plan());
		return PreviewPayload.review(preview, getModpackName(), review.joinOrigin(), writesUnverifiedJar, getSelectedTarget(), review.unverifiedSelectedJarPaths(),
				sourceCatalogue.selectedJarSourceCounts(getSelectedTarget()), review.reviewActions(), continueAction, cancelAction);
	}

	boolean isCurrentAttempt(UpdateAttempt candidate) {
		return attempt.get() == candidate;
	}

	/** The review this engine drives; package-visible so the state machine tests drive the same instance the engine does. */
	ReviewSession reviewSession() {
		return review;
	}

	void interruptInFlight() {
		sourceCatalogue.cancelIfRunning();
		objectAcquisition.interrupt();
	}

	@Override
	public void close() {
		review.confirmationClosed();
		interruptInFlight();
		UpdateAttempt current = attempt.getAndSet(null);
		if (current != null) current.cancel();
		objectAcquisition.release();
		if (closed.compareAndSet(false, true)) {
			if (downloadClient != null) downloadClient.close();
			platformCache.close();
		}
		ScreenManager.restore();
	}

	enum ApplyStatus {
		APPLIED, DEFERRED, FAILED
	}

	/** One reviewed flow's own tail decisions, stated by the flow instead of absorbed into the harness. */
	private record ApplyFlow(String name, Runnable deferredRestart, Consumer<Exception> failed, Runnable closed) {}

	@FunctionalInterface
	private interface FlowBody {
		void run() throws Exception;
	}

}
