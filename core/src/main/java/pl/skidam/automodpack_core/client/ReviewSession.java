package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.loader.PinnedMods;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.screen.ReviewActions;
import pl.skidam.automodpack_core.screen.ReviewPayload;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.JournalMirror;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;

/**
 * The player-facing review of a planned update: the confirmation state machine, the first-install consent, the
 * welcome/preview payloads, and every screen action a review offers. Apply and restart decisions stay with the
 * engine; the session drives them through it and owns when the review is open at all.
 */
final class ReviewSession {
	/**
	 * STARTED is never read as a value, but it is not dead: the PREVIEWING→STARTED CAS is the load-bearing guard that
	 * rejects a second confirm click while an advance runs, and {@link #confirmationClosed} deliberately refuses to
	 * cancel a commit that already began (an executing plan is a durable fact).
	 */
	enum ConfirmationState {
		INACTIVE, WAITING, PREVIEWING, STARTED, CANCELLED
	}

	private enum PreviewRequestResult {
		PREVIEW_SHOWN, PREVIEW_NOT_SHOWN, APPLIED, DEFERRED, FAILED
	}

	private final ModpackUpdater updater;
	private final ClientStorage storage;
	private final SourceCatalogue sourceCatalogue;
	private final AtomicBoolean playerCancelled;
	private final AtomicReference<ConfirmationState> confirmationState = new AtomicReference<>(ConfirmationState.INACTIVE);
	private boolean firstConnection;
	private Map<String, UpdatePlan.FileState> firstInstallLocalModFiles = Map.of();
	private Map<String, UpdatePlan.FileState> consentedLocalModFiles = Map.of();

	ReviewSession(ModpackUpdater updater, ClientStorage storage, SourceCatalogue sourceCatalogue, AtomicBoolean playerCancelled) {
		this.updater = updater;
		this.storage = storage;
		this.sourceCatalogue = sourceCatalogue;
		this.playerCancelled = playerCancelled;
	}

	boolean firstConnection() {
		return firstConnection;
	}

	void firstConnection(boolean value) {
		firstConnection = value;
	}

	/** Clears any earlier review's leftover consent so a launch apply can never inherit it. */
	void resetLocalModConsent() {
		consentedLocalModFiles = Map.of();
	}

	Map<String, UpdatePlan.FileState> consentedLocalModFiles() {
		return consentedLocalModFiles;
	}

	/** The actions and live polls a review-backed screen may drive; the seam type, backed by this session. */
	ReviewActions reviewActions() {
		return new ReviewActions(this::setFirstInstallLocalModCleanup, this::startConfirmedUpdate, this::reselectAndPreview, this::cancelConfirmation, this::cancelFromPlayer,
				() -> confirmationState.get() == ConfirmationState.WAITING, () -> confirmationState.get() == ConfirmationState.CANCELLED, this::isCancelledByPlayer,
				sourceCatalogue::sourceAvailability);
	}

	/** The first-install welcome snapshot; everything is settled before the screen opens, so the review never moves under it. */
	private ReviewPayload reviewPayload() {
		long uncached;
		try {
			uncached = updater.uncachedSelectedTargetBytes();
		} catch (IOException e) {
			// The stat is informational; the acquisition after confirm reports a real failure if the store is unreadable.
			LOGGER.warn("Cannot measure the first-install download cost", e);
			uncached = -1;
		}
		return new ReviewPayload(updater.getSelectedTarget(), reviewCatalogue(), getFirstInstallPatchNotes(), joinOrigin(), unverifiedSelectedJarPaths(), firstInstallLocalModPaths(),
				uncached < 0 ? OptionalLong.empty() : OptionalLong.of(uncached), reviewActions());
	}

	public List<JournalEntry> getFirstInstallPatchNotes() {
		try {
			return new JournalMirror(storage).entries(updater.getSelectedTarget().manifest().modpackId());
		} catch (IOException e) {
			// The mirror was verified at the head fetch moments ago; an unreadable mirror only hides the history entry.
			LOGGER.warn("Journal mirror is unreadable; first-install history is unavailable", e);
			return List.of();
		}
	}

	/** Minecraft join target as `host:port` from the connection origin, or an empty string when offline. */
	public String joinOrigin() {
		if (updater.connectionInfo() == null) return "";
		return updater.connectionInfo().origin.getHostString() + ":" + updater.connectionInfo().origin.getPort();
	}

	/** Direct regular files currently visible in the loader's standard mods directory during first install. */
	List<String> firstInstallLocalModPaths() {
		return List.copyOf(firstInstallLocalModFiles.keySet());
	}

	/** Sets the first-install cleanup consent represented in the canonical update plan. */
	void setFirstInstallLocalModCleanup(boolean archive) {
		if (!firstConnection || confirmationState.get() != ConfirmationState.WAITING) return;
		consentedLocalModFiles = archive ? firstInstallLocalModFiles : Map.of();
	}

	/** First-install review catalogue with Modrinth/CurseForge pages from the completed lookup. */
	public ChangeSet reviewCatalogue() {
		SelectedModpackTarget selectedTarget = updater.getSelectedTarget();
		if (selectedTarget == null) return ChangeSet.empty();
		return ChangeSet.catalogue(selectedTarget.manifest(), ChangeSet.Kind.ADDED, selectedTarget.selection().selectedGroups()).withReferences(sourceCatalogue::mainPageUrlsForCatalogue);
	}

	/** Selected jar paths of the selected target without a Modrinth/CurseForge hash hit. */
	public List<String> unverifiedSelectedJarPaths() {
		return sourceCatalogue.unverifiedSelectedJarPaths(updater.getSelectedTarget());
	}

	/** True when the plan would write a gated jar that has no first-party hit. */
	public boolean planWritesUnverifiedJar(UpdatePlan plan) {
		return sourceCatalogue.planWritesUnverifiedJar(plan);
	}

	/**
	 * Opens the first-install review: scans the loader-visible mods for the cleanup consent, arms the confirmation, and
	 * shows the welcome screen. The caller has already established that the pack has no local state.
	 */
	void beginFirstInstallReview() throws IOException {
		firstConnection = true;
		updater.fullDownload = true;
		LOGGER.info("First-time install; scanning existing mods before the review screen");
		sourceCatalogue.startSourceFetch();
		firstInstallLocalModFiles = updater.storedTarget() == null ? scanFirstInstallLocalMods() : Map.of();
		if (!beginConfirmation()) throw new IllegalStateException("Modpack confirmation is already active");
		ScreenManager.welcome(reviewPayload());
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

	public void startConfirmedUpdate() {
		if (!confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.PREVIEWING)) {
			LOGGER.info("Ignoring modpack download confirmation while another confirmation run is still active");
			return;
		}
		ModpackUpdater.executor().execute(() -> startUpdate(true));
	}

	public void cancelConfirmation() {
		if (!confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.CANCELLED)) return;
		updater.close();
	}

	/**
	 * Stops the in-flight update work after the player backed out of the preparing screen. The flag stays raised
	 * until the draining work observes it; only then is the confirmation seam restored (or the updater closed),
	 * so a follow-up confirmation can never race a still-draining run.
	 */
	public void cancelFromPlayer() {
		if (!playerCancelled.compareAndSet(false, true)) return;
		LOGGER.info("Modpack update cancelled by the player");
		updater.interruptInFlight();
	}

	public boolean isCancelledByPlayer() {
		return playerCancelled.get() || updater.downloadCancelled();
	}

	boolean abortedByPlayer(Throwable cause) {
		return isCancelledByPlayer() || CertificateTrustCancelledException.is(cause);
	}

	/** Applies a new group selection and re-enters the preview path from confirm or preview customize. */
	public void reselectAndPreview(SelectionIntent intent) {
		updater.reselectTarget(intent);
		updater.beginAttempt(null);
		confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.WAITING);
		if (firstConnection && confirmationState.get() == ConfirmationState.WAITING) {
			ScreenManager.welcome(reviewPayload());
			return;
		}
		ScreenManager.waiting(this::cancelFromPlayer);
		ModpackUpdater.executor().execute(() -> startUpdate(true));
	}

	/** Returns the review to the confirmation seam once drained work observes the player's cancellation. */
	private void confirmCancellationHandled() {
		if (confirmationState.get() == ConfirmationState.WAITING || confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.WAITING)) {
			clearPlayerCancel();
			return;
		}
		updater.close();
	}

	/** Returns {@link ModpackUpdater.UpdateOutcome#REVIEW_OPENED} only when the review preview was accepted for display; every other outcome still owns the screen. */
	ModpackUpdater.UpdateOutcome startUpdate(boolean showWaitingScreen) {
		try {
			updater.requireLiveConnection();
			if (showWaitingScreen) ScreenManager.waiting(this::cancelFromPlayer);
			ModpackUpdater.UpdateOutcome outcome = switch (requestUpdatePreview()) {
				case PREVIEW_SHOWN -> ModpackUpdater.UpdateOutcome.REVIEW_OPENED;
				case APPLIED -> {
					LOGGER.info("Applied an already-authorized no-op update without opening a review screen");
					yield ModpackUpdater.UpdateOutcome.APPLIED;
				}
				case DEFERRED -> {
					LOGGER.info("Already-authorized no-op update was deferred to the detached helper");
					yield ModpackUpdater.UpdateOutcome.INCOMPLETE;
				}
				case FAILED -> {
					if (isCancelledByPlayer()) {
						confirmCancellationHandled();
						yield ModpackUpdater.UpdateOutcome.INCOMPLETE;
					}
					LOGGER.error("Already-authorized no-op update failed; the installed generation was not advanced");
					yield ModpackUpdater.UpdateOutcome.INCOMPLETE;
				}
				case PREVIEW_NOT_SHOWN -> {
					if (isCancelledByPlayer()) {
						confirmCancellationHandled();
						yield ModpackUpdater.UpdateOutcome.INCOMPLETE;
					}
					LOGGER.warn("Update preview could not be shown; leaving the installed generation unchanged");
					yield ModpackUpdater.UpdateOutcome.INCOMPLETE;
				}
			};
			if (outcome == ModpackUpdater.UpdateOutcome.REVIEW_OPENED) return outcome; // the updater stays open; the review flow owns it now
			updater.close();
			return outcome;
		} catch (Exception e) {
			if (updater.downloadCancelled()) {
				updater.close();
				return ModpackUpdater.UpdateOutcome.INCOMPLETE;
			}
			if (abortedByPlayer(e) || confirmationState.get() == ConfirmationState.WAITING) {
				if (abortedByPlayer(e)) LOGGER.warn("Modpack update preparation was aborted by the player", e);
				confirmCancellationHandled();
				return ModpackUpdater.UpdateOutcome.INCOMPLETE;
			}
			updater.close();
			ModpackUpdater.showUpdateFailure(e);
			return ModpackUpdater.UpdateOutcome.INCOMPLETE;
		}
	}

	private PreviewRequestResult requestUpdatePreview() throws Exception {
		updater.getSelectedTarget(); // the engine's requireNonNull states the unavailable-target error
		if (isCancelledByPlayer()) return PreviewRequestResult.PREVIEW_NOT_SHOWN;
		sourceCatalogue.startSourceFetch();
		updater.requireLiveConnection();
		UpdateSession session = updater.beginUpdateAttempt();
		session.prepare(true, false);
		if (isCancelledByPlayer()) return PreviewRequestResult.PREVIEW_NOT_SHOWN;
		ClientUpdatePlanBuilder.PreparedPlan prepared = session.prepared();
		if (firstConnection && confirmationState.get() == ConfirmationState.PREVIEWING) {
			session.approve();
			if (!confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.STARTED)) return PreviewRequestResult.PREVIEW_NOT_SHOWN;
			return previewResult(updater.applyApprovedPlan(session, System.currentTimeMillis()));
		}
		if (!session.requiresPlayerReview()) {
			session.approve();
			return previewResult(updater.applyApprovedPlan(session, System.currentTimeMillis()));
		}
		Runnable continueAction = () -> {
			if (!updater.isCurrentAttempt(session)) return;
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
					if (updater.isCurrentAttempt(session)) session.cancel();
					confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.WAITING);
				}
				: () -> {
					if (updater.isCurrentAttempt(session)) session.cancel();
					detachOnDeclinedUpdate();
					updater.close();
				};
		return requestPreparedPlanPreview(session, prepared, continueAction, cancelAction)
				? PreviewRequestResult.PREVIEW_SHOWN
				: PreviewRequestResult.PREVIEW_NOT_SHOWN;
	}

	private boolean requestPreparedPlanPreview(UpdateSession session, ClientUpdatePlanBuilder.PreparedPlan prepared, Runnable continueAction, Runnable cancelAction) throws IOException {
		UpdatePreview preview = session.preview(UpdateSession.InstalledTokenRule.ACTIVE_BOOKMARK)
				.withReferences(sourceCatalogue.resolveMainPageReferences(prepared));
		return ScreenManager.preview(updater.previewPayload(preview, (Runnable) () -> ModpackUpdater.executor().execute(continueAction), cancelAction));
	}

	private void startUpdateAfterPreview(UpdateSession reviewed) {
		long start = System.currentTimeMillis();
		if (reviewed == null || !updater.isCurrentAttempt(reviewed) || !reviewed.isApproved()) {
			LOGGER.warn("Update approval callback arrived without an approved prepared plan");
			updater.close();
			return;
		}
		updater.applyApprovedPlan(reviewed, start);
	}

	/**
	 * Declining a reviewed advance of the active generation is local sovereignty: the pack stops syncing until the
	 * player attaches again. Declines without an active generation, or of the already-active generation, change nothing.
	 */
	private void detachOnDeclinedUpdate() {
		try {
			new ClientGenerationStore(storage).detachOnDeclinedAdvance(updater.getSelectedTarget().manifest().modpackId(), updater.getSelectedTarget().document().contentToken());
		} catch (IOException e) {
			LOGGER.warn("The declined update could not be recorded as detachment", e);
		}
	}

	private PreviewRequestResult previewResult(ModpackUpdater.ApplyStatus status) {
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

	/** The confirmation seam closes with the updater: an open review must not outlive its engine. */
	void confirmationClosed() {
		confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.CANCELLED);
		confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.CANCELLED);
	}
}
