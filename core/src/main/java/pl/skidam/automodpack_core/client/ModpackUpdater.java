package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.client.RestartDecision.ApplyResult;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.PinnedMods;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.JournalMirror;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;

public class ModpackUpdater implements AutoCloseable {
	public Changelogs changelogs = new Changelogs();
	public boolean fullDownload = false;
	private boolean firstConnection;
	private SelectedModpackTarget selectedTarget;
	private ModpackJsons.ModpackContentFields serverModpackContent;
	private final ConnectionJsons.ConnectionInfo connectionInfo;
	private final DownloadClient downloadClient;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicBoolean playerCancelled = new AtomicBoolean();
	private final AtomicReference<ConfirmationState> confirmationState = new AtomicReference<>(ConfirmationState.INACTIVE);
	private final UpdateLoopDetector updateLoopDetector;
	private final ClientStorage storage;
	private final PlatformCache platformCache;
	private final ClientUpdatePlanBuilder planBuilder;
	private final SourceCatalogue sourceCatalogue;
	private final ProjectionLoader projectionLoader;
	private final ModpackObjectAcquisition objectAcquisition;
	private final AtomicReference<UpdateAttempt> attempt = new AtomicReference<>();
	private Map<String, UpdatePlan.FileState> firstInstallLocalModFiles = Map.of();
	private Map<String, UpdatePlan.FileState> consentedLocalModFiles = Map.of();
	/**
	 * The attaching intent of an explicitly requested sync. Detachment ends only through this intent: an applied plan
	 * clears the flag inside the commit, and a requested sync that finds nothing to apply clears it on its early exit.
	 * Detachment is declared by explicit entry points; nothing else ever clears it.
	 */
	private boolean attaching;
	public record SourceAvailability(int totalFiles, int resolvedFiles, boolean complete, boolean cancelled) {}

	private String getModpackName() {
		return serverModpackContent.modpackName;
	}

	public SelectedModpackTarget getSelectedTarget() {
		return Objects.requireNonNull(selectedTarget, "Selected modpack target is unavailable");
	}

	public List<JournalEntry> getFirstInstallPatchNotes() {
		try {
			return new JournalMirror(storage).entries(getSelectedTarget().manifest().modpackId());
		} catch (IOException e) {
			// The mirror was verified at the head fetch moments ago; an unreadable mirror only hides the history entry.
			LOGGER.warn("Journal mirror is unreadable; first-install history is unavailable", e);
			return List.of();
		}
	}

	public SourceAvailability getSourceAvailability() {
		return sourceCatalogue.sourceAvailability();
	}

	/** Direct regular files currently visible in the loader's standard mods directory during first install. */
	public List<String> firstInstallLocalModPaths() {
		return List.copyOf(firstInstallLocalModFiles.keySet());
	}

	public int firstInstallLocalModCount() {
		return firstInstallLocalModFiles.size();
	}

	/** Sets the first-install cleanup consent represented in the canonical update plan. */
	public void setFirstInstallLocalModCleanup(boolean archive) {
		if (!firstConnection || confirmationState.get() != ConfirmationState.WAITING) return;
		consentedLocalModFiles = archive ? firstInstallLocalModFiles : Map.of();
	}

	/** Builds a reviewable switch plan for an installed generation, acquiring selected objects when necessary. */
	public UpdatePreview previewInstalledSwitch() throws Exception {
		if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Installed modpack target is unavailable");
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		boolean projectionPresent = active != null && Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS);
		if (projectionPresent && selectedTarget.manifest().modpackId().equals(active.modpackId)
				&& selectedTarget.document().contentToken().equals(active.contentToken)
				&& Objects.equals(selectedTarget.expectedPriorIntent(), selectedTarget.selection().intent()))
			throw new IllegalArgumentException("Installed modpack target generation and group selection are already active");
		UpdateSession switchAttempt = beginUpdateAttempt();
		switchAttempt.prepare(true, true);
		return switchAttempt.preview(UpdateSession.InstalledTokenRule.ACTIVE_OR_MIRROR_HEAD);
	}

	/** Applies the last installed-generation switch plan through the normal atomic transaction executor. */
	public void applyInstalledSwitch() throws Exception {
		UpdateAttempt current = attempt.get();
		if (!(current instanceof UpdateSession switchPlan) || selectedTarget == null) throw new IllegalStateException("Installed modpack switch was not prepared");
		// The confirm click that reached this action is the review's consent; commit itself refuses an unapproved plan.
		switchPlan.approve();
		// The switch flow reports its failure through its own caller, so its failure handling carries the failure out of the harness.
		AtomicReference<Exception> propagated = new AtomicReference<>();
		runReviewedFlow(new ApplyFlow("Installed modpack switch", () -> new ReLauncher(UpdateType.SELECT, changelogs).restart(false), propagated::set, this::close),
				() -> restartAfterApply(switchPlan.commit()));
		if (propagated.get() != null) throw propagated.get();
	}

	/**
	 * Applies the last prepared switch as a rollback to an older generation: detachment is declared before the commit so
	 * the published active state keeps the flag, making the rollback itself the declaration of local sovereignty.
	 */
	public void applyGenerationRollback() throws Exception {
		if (selectedTarget == null) throw new IllegalStateException("Generation rollback was not prepared");
		new ClientGenerationStore(storage).declareDetached(selectedTarget.manifest().modpackId());
		applyInstalledSwitch();
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
	public long uncachedSelectedTargetBytes() throws IOException {
		long bytes = 0;
		for (var item : missingSelectedTargetObjects()) bytes += item.size;
		return bytes;
	}

	private ModpackJsons.ModpackContentFields storedTarget() throws IOException {
		return ClientProjectionView.open(storage).target();
	}

	private void selectTarget(SelectionIntent intent) {
		Objects.requireNonNull(intent, "intent");
		SelectedModpackTarget current = getSelectedTarget();
		SelectedModpackTarget replacement = SelectedModpackTarget.prepare(current.document(), current.expectedPriorIntent(), intent, current.platform());
		selectedTarget = replacement;
		serverModpackContent = replacement.flatTarget();
	}

	public ConfirmationState getConfirmationState() {
		return confirmationState.get();
	}

	/** Minecraft join target as `host:port` from the connection origin, or an empty string when offline. */
	public String joinOrigin() {
		if (connectionInfo == null) return "";
		return connectionInfo.origin.getHostString() + ":" + connectionInfo.origin.getPort();
	}

	public void startConfirmedUpdate() {
		if (!confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.PREVIEWING)) {
			LOGGER.info("Ignoring modpack download confirmation while another confirmation run is still active");
			return;
		}
		DownloadClient.NET_EXECUTOR.execute(() -> startUpdate(true));
	}

	public void cancelConfirmation() {
		if (!confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.CANCELLED)) return;
		close();
	}

	/**
	 * Stops the in-flight update work after the player backed out of the preparing screen. The flag stays raised
	 * until the draining work observes it; only then is the confirmation seam restored (or the updater closed),
	 * so a follow-up confirmation can never race a still-draining run.
	 */
	public void cancelFromPlayer() {
		if (!playerCancelled.compareAndSet(false, true)) return;
		LOGGER.info("Modpack update cancelled by the player");
		interruptInFlight();
	}

	public boolean isCancelledByPlayer() {
		return playerCancelled.get() || objectAcquisition.downloadCancelled();
	}

	private boolean abortedByPlayer(Throwable cause) {
		return isCancelledByPlayer() || CertificateTrustCancelledException.is(cause);
	}

	/** Applies a new group selection and re-enters the preview path from confirm or preview customize. */
	public void reselectAndPreview(SelectionIntent intent) {
		selectTarget(intent);
		UpdateAttempt previous = attempt.getAndSet(null);
		if (previous != null) previous.cancel();
		confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.WAITING);
		if (firstConnection && confirmationState.get() == ConfirmationState.WAITING) {
			ScreenManager.welcome(this);
			return;
		}
		ScreenManager.waiting(this::cancelFromPlayer);
		DownloadClient.NET_EXECUTOR.execute(() -> startUpdate(true));
	}

	/** First-install review catalogue with Modrinth/CurseForge pages from the completed lookup. */
	public ChangeSet reviewCatalogue() {
		if (selectedTarget == null) return ChangeSet.empty();
		return ChangeSet.catalogue(selectedTarget.manifest(), ChangeSet.Kind.ADDED, selectedTarget.selection().selectedGroups()).withReferences(sourceCatalogue::mainPageUrlsForCatalogue);
	}

	/** Selected jar paths of the selected target without a Modrinth/CurseForge hash hit. */
	public List<String> unverifiedSelectedJarPaths() {
		return sourceCatalogue.unverifiedSelectedJarPaths(selectedTarget);
	}

	/** True when the plan would write a gated jar that has no first-party hit. */
	public boolean planWritesUnverifiedJar(UpdatePlan plan) {
		return sourceCatalogue.planWritesUnverifiedJar(plan);
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
		this.objectAcquisition = new ModpackObjectAcquisition(this.storage, this.platformCache, this.sourceCatalogue, this.planBuilder, this.connectionInfo, this.downloadClient,
				this.playerCancelled, this::getModpackName);
	}

	/** A session for one update attempt against the currently selected target, carrying this attempt's consent and attach intent. */
	private UpdateSession newSession() {
		return new UpdateSession(storage, planBuilder, objectAcquisition, sourceCatalogue, changelogs, connectionInfo, getSelectedTarget(), firstConnection,
				consentedLocalModFiles, attaching);
	}

	/** Replaces any in-flight attempt so prepare, review, and commit cannot drift across two sessions. */
	private <T extends UpdateAttempt> T beginAttempt(T next) {
		UpdateAttempt previous = attempt.getAndSet(next);
		if (previous != null) previous.cancel();
		return next;
	}

	private UpdateSession beginUpdateAttempt() {
		return beginAttempt(newSession());
	}

	private RemovalAttempt requireRemoval(RemovalAttempt.Kind kind) {
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
		/** Cancelled by the player, deferred to a restart, or failed; the caller still owns the screen. */
		INCOMPLETE
	}

	/**
	 * When {@code showWaitingScreen} is false a player-facing screen already owns the wait and shows its own busy state.
	 * Returns {@link UpdateOutcome#APPLIED} exactly when the update ran inline to completion; {@link
	 * UpdateOutcome#REVIEW_OPENED} when a player-facing flow took over (first-install welcome, or a review preview
	 * accepted for display); {@link UpdateOutcome#INCOMPLETE} when the flow was cancelled, deferred to a restart, or
	 * failed, so the caller still owns the screen either way.
	 */
	public UpdateOutcome processModpackUpdate(boolean showWaitingScreen) {
		if (preload) {
			applySelectedTargetWithoutReview(false);
			return UpdateOutcome.APPLIED;
		}

		try {
			requireLiveConnection();
			if (showWaitingScreen) ScreenManager.waiting(this::cancelFromPlayer);

			if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Selected modpack target is unavailable");

			// Handle a modpack installed for the first time: the local-mod consent and group defaults only apply here
			if (!new ClientGenerationStore(storage).hasLocalState(selectedTarget.manifest().modpackId())) {
				firstConnection = true;
				fullDownload = true;
				LOGGER.info("First-time install; scanning existing mods before the review screen");
				sourceCatalogue.startSourceFetch();
				firstInstallLocalModFiles = storedTarget() == null ? scanFirstInstallLocalMods() : Map.of();
				if (!beginConfirmation()) throw new IllegalStateException("Modpack confirmation is already active");
				ScreenManager.welcome(this);
				return UpdateOutcome.REVIEW_OPENED;
			} else if (storage.readActiveState() == null || !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) {
				// Handle an installed modpack without an active projection: reactivate it through the reviewed switch plan
				return startInstalledSwitch(showWaitingScreen);
			} else {
				// Handle existing modpack
				ModpackUtils.reprotectActiveFiles(serverModpackContent, storage);

				return startUpdate(showWaitingScreen);
			}
		} catch (UpdateDeferredException e) {
			close();
			if (isCancelledByPlayer()) return UpdateOutcome.INCOMPLETE;
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(UpdateType.UPDATE, changelogs).restart(preload);
			return UpdateOutcome.INCOMPLETE;
		} catch (Exception e) {
			close();
			if (abortedByPlayer(e)) return UpdateOutcome.INCOMPLETE;
			showUpdateFailure(e);
			return UpdateOutcome.INCOMPLETE;
		}
	}

	private Map<String, UpdatePlan.FileState> scanFirstInstallLocalMods() throws IOException {
		Path modsDirectory = storage.modsDirectory();
		if (Files.notExists(modsDirectory, LinkOption.NOFOLLOW_LINKS)) return Map.of();
		if (Files.isSymbolicLink(modsDirectory) || !Files.isDirectory(modsDirectory, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Loader-visible mods directory is not a real directory: " + modsDirectory);
		Path loadedMod = THIS_MOD_JAR == null ? null : THIS_MOD_JAR.toAbsolutePath().normalize();
		Map<String, UpdatePlan.FileState> observed = new TreeMap<>();
		Set<String> listedPins = PinnedMods.index(clientConfig == null ? List.of() : clientConfig.pinnedModIds);
		try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory()); Stream<Path> stream = Files.list(modsDirectory)) {
			for (Path path : stream.toList()) {
				if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
				Path normalized = path.toAbsolutePath().normalize();
				if (loadedMod != null && normalized.equals(loadedMod)) continue;
				if (!listedPins.isEmpty()) {
					FileInspection.Mod inspected = modCache.getModOrNull(normalized, cache);
					if (inspected != null && PinnedMods.matches(listedPins, inspected.IDs())) continue;
				}
				String relative = LogicalPath.normalize(storage.gameDirectory().relativize(normalized).toString());
				String hash = cache.getOrComputeHash(normalized);
				if (hash == null) throw new IOException("Cannot hash local mod file: " + normalized);
				observed.put(relative, new UpdatePlan.FileState(hash, Files.size(normalized), true));
			}
		}
		return Collections.unmodifiableMap(observed);
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
			if (!preload && !abortedByPlayer(e)) showUpdateFailure(e);
		}, this::closeLaunchApply), () -> launchApply(applyFirstInstall));
	}

	/** The launch apply's own steps: resolve the target, prepare without a preview, and commit the approved plan. */
	private void launchApply(boolean applyFirstInstall) throws Exception {
		if (selectedTarget == null || serverModpackContent == null) {
			LOGGER.info("Skipping launch apply because no resolved target is available");
			return;
		}
		requireLiveConnection();
		firstConnection = !new ClientGenerationStore(storage).hasLocalState(selectedTarget.manifest().modpackId());
		consentedLocalModFiles = Map.of();
		if (firstConnection && !applyFirstInstall) {
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
		if (planWritesUnverifiedJar(launch.prepared().plan())) {
			LOGGER.warn("Launch apply aborted: unverified jars will not be written during preload; leaving the live pack unchanged");
			return;
		}
		if (!firstConnection && !launch.requiresReconciliation(storedTarget())) {
			LOGGER.info("Launch apply reused the active projection");
			return;
		}
		launch.approve();
		ApplyResult applyResult = launch.commit();
		LOGGER.info("Launch apply completed; restart required: {} Took: {}ms", applyResult.requiresRestart(), System.currentTimeMillis() - start);
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

	private void finishLaunchApply(ApplyResult applyResult) {
		if (!preload) {
			restartAfterApply(applyResult);
			return;
		}
		if (!RestartDecision.requiresRestartAtPreload(applyResult.restartReasons())) {
			LOGGER.info("Launch apply needs no restart at preload; hot-loading the fresh pack in this boot");
			return;
		}
		new ReLauncher(RestartDecision.launchRestartType(firstConnection, applyResult.restartReasons()), changelogs).restart(true);
	}

	// Load the already-installed modpack without contacting the server or
	// reconciling local files against it. Used when update-on-launch is disabled
	// so the user can freely add/remove mods (e.g. a binary search) without
	// AutoModpack restoring or deleting them.
	public void loadModpack() throws Exception {
		projectionLoader.loadModpack();
	}

	public boolean requiresUpdateBeforeLogin(ModpackUtils.UpdateCheckResult result) throws Exception {
		if (result == null || result.requiresUpdate()) return true;
		if (storage.readActiveState() == null || !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return true;
		if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Selected modpack target is unavailable");
		return newSession().requiresUpdateBeforeLogin(result);
	}

	// Build the removal plan without changing the installed files.
	public UpdatePreview previewRemoval() throws Exception {
		return beginAttempt(new RemovalAttempt(storage, planBuilder, changelogs, RemovalAttempt.Kind.REMOVAL)).preview();
	}

	public UpdatePreview previewDeactivation() throws Exception {
		return beginAttempt(new RemovalAttempt(storage, planBuilder, changelogs, RemovalAttempt.Kind.DEACTIVATION)).preview();
	}

	public record LifecycleApply(boolean success, boolean restartRequired) {}

	public LifecycleApply deactivateModpack() throws Exception {
		return commitRemoval(RemovalAttempt.Kind.DEACTIVATION);
	}

	// Remove the installed modpack and restore baseline files before metadata cleanup.
	public LifecycleApply removeModpack() throws Exception {
		return commitRemoval(RemovalAttempt.Kind.REMOVAL);
	}

	private LifecycleApply commitRemoval(RemovalAttempt.Kind kind) throws Exception {
		RemovalAttempt removal = requireRemoval(kind);
		// The confirm click is the review's consent; commit itself refuses an unapproved plan.
		removal.approve();
		ApplyResult result = removal.commit();
		afterRemovalApply(result);
		return new LifecycleApply(true, result.requiresRestart());
	}

	/** Removal has no in-game content load: only a plan that names a restart reason asks the player to restart. */
	private void afterRemovalApply(ApplyResult applyResult) {
		if (applyResult.requiresRestart()) restartAfterApply(applyResult);
		else updateLoopDetector.clear();
	}

	/** Post-apply restart for a running game: the updater is the screen adapter, so this decision stays here. */
	private void restartAfterApply(ApplyResult applyResult) {
		if (!preload && (!changelogs.changedFiles().isEmpty() || !changelogs.removedFiles().isEmpty())) SessionUpdateState.markAppliedContentNotLoaded();
		if (!applyResult.requiresRestart()) {
			updateLoopDetector.clear();
			if (!preload && (!changelogs.changedFiles().isEmpty() || !changelogs.removedFiles().isEmpty())) {
				LOGGER.info("Update applied with {} changed and {} removed files, but they cannot load into the running game; asking the player to restart", changelogs.changedFiles().size(),
						changelogs.removedFiles().size());
				ScreenManager.restart(fullDownload ? UpdateType.FULL : UpdateType.UPDATE, changelogs);
				return;
			}
			ScreenManager.completeWithoutRestart();
			return;
		}
		String fingerprint = RestartDecision.stateFingerprint(storage, applyResult);
		if (updateLoopDetector.evaluateAndRecord(fingerprint).decision() == UpdateLoopDetector.Decision.SUPPRESS) {
			LOGGER.error("Automatic restart loop detected. AutoModpack already requested two rapid restarts for the same correction state.");
			LOGGER.error("Corrections were applied but still require a restart: {}", String.join(", ", applyResult.reasonDescriptions()));
			LOGGER.error("Another automatic restart was suppressed. The modpack may not be fully active; inspect the surrounding logs and report recurring issues at https://github.com/Skidamek/AutoModpack/issues");
			return;
		}
		new ReLauncher(RestartDecision.applyRestartType(fullDownload, applyResult.restartReasons()), changelogs).restart(false);
	}

	/** Returns the updater to the confirmation seam once drained work observes the player's cancellation. */
	private void confirmCancellationHandled() {
		if (confirmationState.get() == ConfirmationState.WAITING || confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.WAITING)) {
			clearPlayerCancel();
			return;
		}
		close();
	}

	/** Returns {@link UpdateOutcome#REVIEW_OPENED} only when the review preview was accepted for display; every other outcome still owns the screen. */
	private UpdateOutcome startUpdate(boolean showWaitingScreen) {
		try {
			requireLiveConnection();
			if (showWaitingScreen) ScreenManager.waiting(this::cancelFromPlayer);
			UpdateOutcome outcome = switch (requestUpdatePreview()) {
				case PREVIEW_SHOWN -> UpdateOutcome.REVIEW_OPENED;
				case APPLIED -> {
					LOGGER.info("Applied an already-authorized no-op update without opening a review screen");
					yield UpdateOutcome.APPLIED;
				}
				case DEFERRED -> {
					LOGGER.info("Already-authorized no-op update was deferred to the detached helper");
					yield UpdateOutcome.INCOMPLETE;
				}
				case FAILED -> {
					if (isCancelledByPlayer()) {
						confirmCancellationHandled();
						yield UpdateOutcome.INCOMPLETE;
					}
					LOGGER.error("Already-authorized no-op update failed; the installed generation was not advanced");
					yield UpdateOutcome.INCOMPLETE;
				}
				case PREVIEW_NOT_SHOWN -> {
					if (isCancelledByPlayer()) {
						confirmCancellationHandled();
						yield UpdateOutcome.INCOMPLETE;
					}
					LOGGER.warn("Update preview could not be shown; leaving the installed generation unchanged");
					yield UpdateOutcome.INCOMPLETE;
				}
			};
			if (outcome == UpdateOutcome.REVIEW_OPENED) return outcome; // the updater stays open; the review flow owns it now
			close();
			return outcome;
		} catch (Exception e) {
			if (objectAcquisition.downloadCancelled()) {
				close();
				return UpdateOutcome.INCOMPLETE;
			}
			if (abortedByPlayer(e) || confirmationState.get() == ConfirmationState.WAITING) {
				if (abortedByPlayer(e)) LOGGER.warn("Modpack update preparation was aborted by the player", e);
				confirmCancellationHandled();
				return UpdateOutcome.INCOMPLETE;
			}
			close();
			showUpdateFailure(e);
			return UpdateOutcome.INCOMPLETE;
		}
	}

	/**
	 * Presents the switch plan for an installed modpack that has no active projection, instead of replaying the
	 * first-install flow. Returns true only when the preview was accepted for display.
	 */
	private UpdateOutcome startInstalledSwitch(boolean showWaitingScreen) {
		try {
			requireLiveConnection();
			if (showWaitingScreen) ScreenManager.waiting(this::cancelFromPlayer);
			UpdatePreview preview = previewInstalledSwitch();
			if (isCancelledByPlayer()) {
				close();
				return UpdateOutcome.INCOMPLETE;
			}
			UpdateAttempt switchAttempt = attempt.get();
			Runnable continueAction = () -> {
				try {
					if (attempt.get() != switchAttempt) return;
					applyInstalledSwitch();
				} catch (Exception e) {
					if (!abortedByPlayer(e)) showUpdateFailure(e);
				}
			};
			if (!ScreenManager.preview(preview, getModpackName(), this, (Runnable) () -> DownloadClient.NET_EXECUTOR.execute(continueAction), this::close)) {
				LOGGER.warn("Installed modpack switch preview could not be shown; leaving the client without an active modpack");
				close();
				return UpdateOutcome.INCOMPLETE;
			}
			return UpdateOutcome.REVIEW_OPENED;
		} catch (Exception e) {
			if (!abortedByPlayer(e)) showUpdateFailure(e);
			close();
			return UpdateOutcome.INCOMPLETE;
		}
	}

	private void startUpdateAfterPreview(UpdateSession reviewed) {
		long start = System.currentTimeMillis();
		if (reviewed == null || attempt.get() != reviewed || !reviewed.isApproved()) {
			LOGGER.warn("Update approval callback arrived without an approved prepared plan");
			close();
			return;
		}
		applyApprovedPlan(reviewed, start);
	}

	private ApplyStatus applyApprovedPlan(UpdateSession reviewed, long start) {
		if (isCancelledByPlayer()) {
			close();
			return ApplyStatus.FAILED;
		}
		return runReviewedFlow(new ApplyFlow("Update", () -> {
			if (!isCancelledByPlayer()) new ReLauncher(UpdateType.UPDATE, changelogs).restart(preload);
		}, e -> {
			if (abortedByPlayer(e)) LOGGER.warn("Modpack update apply was aborted by the player", e);
			else showUpdateFailure(e);
		}, this::close), () -> {
			ApplyResult applyResult = reviewed.commit();
			LOGGER.info("Update completed! Required restart: {} Took: {}ms", applyResult.requiresRestart(), System.currentTimeMillis() - start);
			restartAfterApply(applyResult);
		});
	}

	private void requireLiveConnection() throws IOException {
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
	private static void showUpdateFailure(Exception e) {
		ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
	}

	private PreviewRequestResult requestUpdatePreview() throws Exception {
		if (selectedTarget == null) throw new IllegalStateException("Selected modpack target is unavailable");
		if (isCancelledByPlayer()) return PreviewRequestResult.PREVIEW_NOT_SHOWN;
		sourceCatalogue.startSourceFetch();
		requireLiveConnection();
		UpdateSession session = beginUpdateAttempt();
		session.prepare(true, false);
		if (isCancelledByPlayer()) return PreviewRequestResult.PREVIEW_NOT_SHOWN;
		ClientUpdatePlanBuilder.PreparedPlan prepared = session.prepared();
		if (firstConnection && confirmationState.get() == ConfirmationState.PREVIEWING) {
			session.approve();
			if (!confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.STARTED)) return PreviewRequestResult.PREVIEW_NOT_SHOWN;
			return previewResult(applyApprovedPlan(session, System.currentTimeMillis()));
		}
		if (!session.requiresPlayerReview()) {
			session.approve();
			return previewResult(applyApprovedPlan(session, System.currentTimeMillis()));
		}
		Runnable continueAction = () -> {
			if (attempt.get() != session) return;
			try {
				session.approve(); // The confirm click is the review's consent; a second click dies here.
			} catch (IllegalStateException e) {
				return;
			}
			if (!confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.STARTED) && firstConnection) return;
			startUpdateAfterPreview(session);
		};
		Runnable cancelAction = firstConnection
				? () -> {
					if (attempt.get() == session) session.cancel();
					confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.WAITING);
				}
				: () -> {
					if (attempt.get() == session) session.cancel();
					detachOnDeclinedUpdate();
					close();
				};
		return requestPreparedPlanPreview(session, prepared, continueAction, cancelAction)
				? PreviewRequestResult.PREVIEW_SHOWN
				: PreviewRequestResult.PREVIEW_NOT_SHOWN;
	}

	private boolean requestPreparedPlanPreview(UpdateSession session, ClientUpdatePlanBuilder.PreparedPlan prepared, Runnable continueAction, Runnable cancelAction) throws IOException {
		UpdatePreview preview = session.preview(UpdateSession.InstalledTokenRule.ACTIVE_BOOKMARK)
				.withReferences(sourceCatalogue.resolveMainPageReferences(prepared));
		return ScreenManager.preview(preview, getModpackName(), this,
				(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(continueAction), cancelAction);
	}

	/**
	 * Declining a reviewed advance of the active generation is local sovereignty: the pack stops syncing until the
	 * player attaches again. Declines without an active generation, or of the already-active generation, change nothing.
	 */
	private void detachOnDeclinedUpdate() {
		try {
			new ClientGenerationStore(storage).detachOnDeclinedAdvance(selectedTarget.manifest().modpackId(), selectedTarget.document().contentToken());
		} catch (IOException e) {
			LOGGER.warn("The declined update could not be recorded as detachment", e);
		}
	}

	private PreviewRequestResult previewResult(ApplyStatus status) {
		return switch (status) {
			case APPLIED -> PreviewRequestResult.APPLIED;
			case DEFERRED -> PreviewRequestResult.DEFERRED;
			case FAILED -> PreviewRequestResult.FAILED;
		};
	}

	private boolean beginConfirmation() {
		return confirmationState.compareAndSet(ConfirmationState.INACTIVE, ConfirmationState.WAITING);
	}

	private boolean clearPlayerCancel() {
		return playerCancelled.compareAndSet(true, false);
	}

	private void interruptInFlight() {
		sourceCatalogue.cancelIfRunning();
		objectAcquisition.interrupt();
	}

	@Override
	public void close() {
		confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.CANCELLED);
		confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.CANCELLED);
		interruptInFlight();
		UpdateAttempt current = attempt.getAndSet(null);
		if (current != null) current.cancel();
		objectAcquisition.release();
		if (closed.compareAndSet(false, true)) {
			if (downloadClient != null) downloadClient.close();
			platformCache.close();
		}
	}

	public enum ConfirmationState {
		INACTIVE, WAITING, PREVIEWING, STARTED, CANCELLED
	}

	private enum ApplyStatus {
		APPLIED, DEFERRED, FAILED
	}

	private enum PreviewRequestResult {
		PREVIEW_SHOWN, PREVIEW_NOT_SHOWN, APPLIED, DEFERRED, FAILED
	}

	/** One reviewed flow's own tail decisions, stated by the flow instead of absorbed into the harness. */
	private record ApplyFlow(String name, Runnable deferredRestart, Consumer<Exception> failed, Runnable closed) {}

	@FunctionalInterface
	private interface FlowBody {
		void run() throws Exception;
	}

}
