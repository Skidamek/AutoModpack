package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.PinnedMods;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.JournalMirror;
import pl.skidam.automodpack_core.update.ReviewedUpdatePlan;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.update.UpdateReplanRequiredException;
import pl.skidam.automodpack_core.update.UpdateReviewPolicy;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;
import pl.skidam.automodpack_loader_core.DetachedUpdateHelper;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.UpdateTransactionSupport;
import pl.skidam.automodpack_loader_core.client.RestartDecision.ApplyResult;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class ModpackUpdater implements AutoCloseable {
	public Changelogs changelogs = new Changelogs();
	public DownloadManager downloadManager;
	public long totalBytesToDownload = 0;
	public boolean fullDownload = false;
	private boolean firstConnection;
	private SelectedModpackTarget selectedTarget;
	private ModpackJsons.ModpackContentFields serverModpackContent;
	private final Map<ModpackJsons.ModpackContentFields.ModpackContentItem, List<String>> failedDownloads = new ConcurrentHashMap<>();
	private final Map<ModpackJsons.ModpackContentFields.ModpackContentItem, DownloadManager.FailureCategory> failedDownloadCategories = new ConcurrentHashMap<>();
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
	private final RemovalLifecycle removalLifecycle;
	private final ProjectionLoader projectionLoader;
	private ReviewedClientPlan<ClientUpdatePlanBuilder.PreparedPlan> installedSwitchPlan;
	private ReviewedClientPlan<ClientUpdatePlanBuilder.PreparedPlan> reviewedUpdatePlan;
	private Map<String, UpdatePlan.FileState> firstInstallLocalModFiles = Map.of();
	private Map<String, UpdatePlan.FileState> consentedLocalModFiles = Map.of();
	private final Set<String> reservedObjectHashes = new TreeSet<>();
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
				&& Objects.equals(selectedTarget.expectedPriorIntent(), selectedTarget.selection().intent()))
			throw new IllegalArgumentException("Installed modpack target and group selection are already active");
		try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory())) {
			acquireTargetObjects(selectedTarget.flatTarget(), cache, true);
			planBuilder.reconcileEditableState(cache, selectedTarget.flatTarget());
			ClientUpdatePlanBuilder.PreparedPlan prepared = planBuilder.buildPlan(updatePlanInput(true), cache, modCache);
			planBuilder.preparePlanObjects(prepared.plan(), selectedTarget.flatTarget());
			installedSwitchPlan = new ReviewedClientPlan<>(prepared, ReviewedUpdatePlan.pending(prepared.plan()));
			String installedToken;
			if (active != null && selectedTarget.manifest().modpackId().equals(active.modpackId)) installedToken = active.contentToken;
			else installedToken = new ClientGenerationStore(storage).installedRecord(selectedTarget.manifest().modpackId()).map(PackDocument::contentToken).orElse("");
			return updatePreview(prepared.plan(), selectedTarget, installedToken);
		}
	}

	/** Applies the last installed-generation switch plan through the normal atomic transaction executor. */
	public void applyInstalledSwitch() throws Exception {
		ReviewedClientPlan<ClientUpdatePlanBuilder.PreparedPlan> reviewed = installedSwitchPlan;
		if (reviewed == null || selectedTarget == null) throw new IllegalStateException("Installed modpack switch was not prepared");
		if (!reviewed.review().isApproved()) reviewed.review().approve();
		ClientUpdatePlanBuilder.PreparedPlan prepared = reviewed.prepared();
		try {
			recordChangelogs(prepared, selectedTarget);
			ApplyResult applyResult = applyPreparedPlan(reviewed, selectedTarget);
			changelogs.setRestartReasons(applyResult.reasonDescriptions());
			removalLifecycle.restartAfterApply(applyResult);
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Installed modpack switch transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(UpdateType.SELECT, changelogs).restart(false);
		} finally {
			close();
		}
	}

	/** Returns whether the selected installed target needs an authenticated object-transfer session. */
	public boolean requiresSelectedTargetDownload() throws IOException {
		if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Installed modpack target is unavailable");
		try (var cache = FileCache.open(storage.fileCacheDirectory())) {
			planBuilder.populateStoreFromCachedLocations(selectedTarget.flatTarget(), cache);
			return !missingTargetObjects(selectedTarget.flatTarget(), cache).isEmpty();
		}
	}

	private ModpackJsons.ModpackContentFields storedTarget() throws IOException {
		return ClientProjectionView.open(storage).target();
	}

	private void selectTarget(SelectionIntent intent) {
		Objects.requireNonNull(intent, "intent");
		SelectedModpackTarget current = getSelectedTarget();
		SelectedModpackTarget replacement = SelectedModpackTarget.prepare(headFields(current), current.expectedPriorIntent(), intent, current.platform());
		selectedTarget = replacement;
		serverModpackContent = replacement.flatTarget();
	}

	/** Rebuilds the head document the selected target was prepared from, so a new group selection can resolve against it. */
	private static GenerationJsons.HeadDocumentFields headFields(SelectedModpackTarget target) {
		PackDocument document = target.document();
		GenerationJsons.HeadDocumentFields fields = new GenerationJsons.HeadDocumentFields();
		fields.contentToken = document.contentToken();
		fields.policySha1 = document.policySha1();
		fields.createdAt = document.createdAt().toString();
		fields.ownershipLedger = document.ownershipLedger().toFields();
		fields.policy = document.manifest().toFields();
		return fields;
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
		return playerCancelled.get() || downloadManager != null && downloadManager.isCancelled();
	}

	private boolean abortedByPlayer(Throwable cause) {
		return isCancelledByPlayer() || CertificateTrustCancelledException.is(cause);
	}

	/** Applies a new group selection and re-enters the preview path from confirm or preview customize. */
	public void reselectAndPreview(SelectionIntent intent) {
		selectTarget(intent);
		if (reviewedUpdatePlan != null) {
			reviewedUpdatePlan.review().cancel();
			reviewedUpdatePlan = null;
		}
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
		this.removalLifecycle = new RemovalLifecycle(this.storage, this.planBuilder, changelogs, this.updateLoopDetector, () -> fullDownload);
		this.projectionLoader = new ProjectionLoader(this.storage, this::storedTarget);
		this.downloadClient = downloadClient;
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

	/** When {@code showWaitingScreen} is false a player-facing screen already owns the wait and shows its own busy state. */
	public void processModpackUpdate(boolean showWaitingScreen) {
		if (preload) {
			applySelectedTargetWithoutReview(false);
			return;
		}

		try {
			requireLiveConnection();
			if (showWaitingScreen) ScreenManager.waiting(this::cancelFromPlayer);

			if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Selected modpack target is unavailable");

			// Handle a modpack installed for the first time: the local-mod consent and group defaults only apply here
			if (new ClientGenerationStore(storage).installedRecord(selectedTarget.manifest().modpackId()).isEmpty()) {
				firstConnection = true;
				fullDownload = true;
				LOGGER.info("First-time install; scanning existing mods before the review screen");
				sourceCatalogue.startSourceFetch();
				firstInstallLocalModFiles = storedTarget() == null ? scanFirstInstallLocalMods() : Map.of();
				if (!beginConfirmation()) throw new IllegalStateException("Modpack confirmation is already active");
				ScreenManager.welcome(this);
			} else if (storage.readActiveState() == null || !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) {
				// Handle an installed modpack without an active projection: reactivate it through the reviewed switch plan
				startInstalledSwitch(showWaitingScreen);
			} else {
				// Handle existing modpack
				ModpackUtils.reprotectActiveFiles(serverModpackContent, storage);

				startUpdate(showWaitingScreen);
			}
		} catch (UpdateDeferredException e) {
			close();
			if (isCancelledByPlayer()) return;
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(UpdateType.UPDATE, changelogs).restart(preload);
		} catch (Exception e) {
			close();
			if (abortedByPlayer(e)) return;
			ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
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

	private ClientUpdatePlanBuilder.Input updatePlanInput(boolean prepareObjects) {
		Map<String, UpdatePlan.FileState> consent = firstConnection ? consentedLocalModFiles : Map.of();
		return new ClientUpdatePlanBuilder.Input(selectedTarget, selectedTarget.flatTarget(), connectionInfo, clientConfig, prepareObjects, consent);
	}

	/**
	 * Applies the selected pack during launch. Preload has no review screen. The plan's restart reasons are the
	 * authority: copies or deletes in the standard mods folder, a loader-version swap, and the other restart reasons
	 * stop this process so the next launch sees the real {@code mods/} tree. Projection-only work loads in this process.
	 * A deferred transaction restarts for the detached helper. First install waits for in-game review unless
	 * {@code applyFirstInstall} is set (trusted bootstrap).
	 */
	private void applySelectedTargetWithoutReview(boolean applyFirstInstall) {
		try {
			if (selectedTarget == null || serverModpackContent == null) {
				LOGGER.info("Skipping launch apply because no resolved target is available");
				return;
			}
			requireLiveConnection();
			firstConnection = new ClientGenerationStore(storage).installedRecord(selectedTarget.manifest().modpackId()).isEmpty();
			consentedLocalModFiles = Map.of();
			if (firstConnection && !applyFirstInstall) {
				try (var cache = FileCache.open(storage.fileCacheDirectory())) {
					acquireTargetObjects(selectedTarget.flatTarget(), cache, false);
				}
				LOGGER.info("Launch apply is waiting for first-install review");
				return;
			}
			long start = System.currentTimeMillis();
			ClientUpdatePlanBuilder.PreparedPlan prepared = prepareSelectedPlan(false);
			if (planWritesUnverifiedJar(prepared.plan())) {
				LOGGER.warn("Launch apply aborted: unverified jars will not be written during preload; leaving the live pack unchanged");
				return;
			}
			if (!firstConnection && !requiresReconciliation(prepared, storedTarget())) {
				LOGGER.info("Launch apply reused the active projection");
				return;
			}
			ReviewedClientPlan<ClientUpdatePlanBuilder.PreparedPlan> reviewed = new ReviewedClientPlan<>(prepared, ReviewedUpdatePlan.pending(prepared.plan()));
			reviewed.review().approve();
			recordChangelogs(prepared, selectedTarget);
			ApplyResult applyResult = applyPreparedPlan(reviewed, selectedTarget);
			LOGGER.info("Launch apply completed; restart required: {} Took: {}ms", applyResult.requiresRestart(), System.currentTimeMillis() - start);
			finishLaunchApply(applyResult);
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Launch apply transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(UpdateType.UPDATE, changelogs).restart(true);
		} catch (Exception e) {
			LOGGER.error("Failed to apply the selected modpack; no projection changes were made outside the existing transaction guarantees", e);
			if (!preload && !abortedByPlayer(e)) ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
		} finally {
			try {
				if (preload) projectionLoader.loadSelectedActiveProjection();
			} catch (Exception e) {
				LOGGER.error("Failed to load the active modpack projection after launch apply", e);
			}
			close();
		}
	}

	private void finishLaunchApply(ApplyResult applyResult) {
		if (!preload) {
			removalLifecycle.restartAfterApply(applyResult);
			return;
		}
		if (!applyResult.requiresRestart()) return;
		new ReLauncher(RestartDecision.launchRestartType(firstConnection, applyResult.restartReasons()), changelogs).restart(true);
	}

	private static Set<ModpackJsons.ModpackContentFields.ModpackContentItem> uniqueObjects(Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items) {
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> unique = new LinkedHashMap<>();
		for (var item : items) unique.putIfAbsent(item.sha1.toLowerCase(Locale.ROOT), item);
		return new LinkedHashSet<>(unique.values());
	}

	private Set<ModpackJsons.ModpackContentFields.ModpackContentItem> missingTargetObjects(ModpackJsons.ModpackContentFields target, FileCache cache) {
		Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items = target.list == null ? List.of() : target.list;
		return ModpackUtils.identifyUncachedFiles(uniqueObjects(items), cache, storage);
	}

	/** Acquires the complete selected target so every caller uses target state, never a stale generation diff, as its download authority. */
	private int acquireTargetObjects(ModpackJsons.ModpackContentFields target, FileCache cache, boolean playerFacing) throws Exception {
		Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items = target.list == null ? List.of() : target.list;
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> targetObjects = uniqueObjects(items);
		reserveObjects(targetObjects.stream().map(item -> item.sha1).collect(Collectors.toSet()));
		ModpackUtils.populateStoreFromCWD(targetObjects, cache, storage);
		planBuilder.populateStoreFromCachedLocations(target, cache);
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> missing = ModpackUtils.identifyUncachedFiles(targetObjects, cache, storage);
		if (missing.isEmpty()) {
			LOGGER.info("All {} selected modpack objects are already acquired locally", targetObjects.size());
			return 0;
		}

		requireLiveConnection();
		long start = System.currentTimeMillis();
		totalBytesToDownload = missing.stream().mapToLong(item -> Long.parseLong(item.size)).sum();
		FetchManager fetchManager = sourceCatalogue.sourceFetch(missing);
		try {
			if (!downloadModpack(missing, start, fetchManager, playerFacing))
				throw new IOException("One or more selected modpack objects could not be acquired");
		} catch (Exception e) {
			if (downloadManager != null) {
				if (downloadManager.isCancelled()) playerCancelled.compareAndSet(false, true);
				else downloadManager.cancelAllAndShutdown();
			}
			throw e;
		}

		planBuilder.populateStoreFromLogicalProjection(target, cache);
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> stillMissing = ModpackUtils.identifyUncachedFiles(targetObjects, cache, storage);
		if (!stillMissing.isEmpty()) throw new IOException("Verified selected-target objects are still missing after acquisition: " + stillMissing.size());
		if (!playerFacing) LOGGER.info("Launch apply acquired {} complete modpack objects in {}ms", targetObjects.size(), System.currentTimeMillis() - start);
		return missing.size();
	}

	private void reserveObjects(Set<String> hashes) throws IOException {
		reservedObjectHashes.addAll(hashes.stream().map(hash -> hash.toLowerCase(Locale.ROOT)).toList());
		ClientObjectStore.publishOwnership(storage, Set.copyOf(reservedObjectHashes));
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

		try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory())) {
			planBuilder.reconcileEditableState(cache, selectedTarget.flatTarget());
			ClientUpdatePlanBuilder.PreparedPlan prepared = planBuilder.buildPlan(updatePlanInput(false), cache, modCache);
			ModpackJsons.ModpackContentFields installed = storedTarget();
			return requiresReconciliation(prepared, installed);
		}
	}

	// Build the removal plan without changing the installed files.
	public UpdatePreview previewRemoval() throws Exception {
		return removalLifecycle.previewRemoval();
	}

	public UpdatePreview previewDeactivation() throws Exception {
		return removalLifecycle.previewDeactivation();
	}

	public record LifecycleApply(boolean success, boolean restartRequired) {}

	public LifecycleApply deactivateModpack() throws Exception {
		return removalLifecycle.deactivateModpack();
	}

	// Remove the installed modpack and restore baseline files before metadata cleanup.
	public LifecycleApply removeModpack() throws Exception {
		return removalLifecycle.removeModpack();
	}

	/** Returns the updater to the confirmation seam once drained work observes the player's cancellation. */
	private void confirmCancellationHandled() {
		if (confirmationState.get() == ConfirmationState.WAITING || confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.WAITING)) {
			clearPlayerCancel();
			return;
		}
		close();
	}

	private void startUpdate(boolean showWaitingScreen) {
		try {
			requireLiveConnection();
			if (showWaitingScreen) ScreenManager.waiting(this::cancelFromPlayer);
			switch (requestUpdatePreview()) {
				case PREVIEW_SHOWN -> {
					return;
				}
				case APPLIED -> LOGGER.info("Applied an already-authorized no-op update without opening a review screen");
				case DEFERRED -> LOGGER.info("Already-authorized no-op update was deferred to the detached helper");
				case FAILED -> {
					if (isCancelledByPlayer()) {
						confirmCancellationHandled();
						return;
					}
					LOGGER.error("Already-authorized no-op update failed; the installed generation was not advanced");
				}
				case PREVIEW_NOT_SHOWN -> {
					if (isCancelledByPlayer()) {
						confirmCancellationHandled();
						return;
					}
					LOGGER.warn("Update preview could not be shown; leaving the installed generation unchanged");
				}
			}
			close();
		} catch (Exception e) {
			if (downloadManager != null && downloadManager.isCancelled()) {
				close();
				return;
			}
			if (abortedByPlayer(e) || confirmationState.get() == ConfirmationState.WAITING) {
				if (abortedByPlayer(e)) LOGGER.warn("Modpack update preparation was aborted by the player", e);
				confirmCancellationHandled();
				return;
			}
			close();
			ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			return;
		}
	}

	/** Presents the switch plan for an installed modpack that has no active projection, instead of replaying the first-install flow. */
	private void startInstalledSwitch(boolean showWaitingScreen) {
		try {
			requireLiveConnection();
			if (showWaitingScreen) ScreenManager.waiting(this::cancelFromPlayer);
			UpdatePreview preview = previewInstalledSwitch();
			if (isCancelledByPlayer()) {
				close();
				return;
			}
			Runnable continueAction = () -> {
				try {
					applyInstalledSwitch();
				} catch (Exception e) {
					if (!abortedByPlayer(e)) ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
				}
			};
			if (!ScreenManager.preview(preview, getModpackName(), this, (Runnable) () -> DownloadClient.NET_EXECUTOR.execute(continueAction), this::close)) {
				LOGGER.warn("Installed modpack switch preview could not be shown; leaving the client without an active modpack");
				close();
			}
		} catch (Exception e) {
			if (!abortedByPlayer(e)) ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			close();
		}
	}

	private void startUpdateAfterPreview() {
		long start = System.currentTimeMillis();
		ReviewedClientPlan<ClientUpdatePlanBuilder.PreparedPlan> reviewed = reviewedUpdatePlan;
		if (reviewed == null || !reviewed.review().isApproved()) {
			LOGGER.warn("Update approval callback arrived without an approved prepared plan");
			close();
			return;
		}
		applyApprovedPlan(reviewed, start);
	}

	private ApplyStatus applyApprovedPlan(ReviewedClientPlan<ClientUpdatePlanBuilder.PreparedPlan> reviewed, long start) {
		try {
			if (isCancelledByPlayer()) return ApplyStatus.FAILED;
			ClientUpdatePlanBuilder.PreparedPlan prepared = reviewed.prepared();
			recordChangelogs(prepared, selectedTarget);
			ApplyResult applyResult = applyPreparedPlan(reviewed, selectedTarget);
			changelogs.setRestartReasons(applyResult.reasonDescriptions());
			LOGGER.info("Update completed! Required restart: {} Took: {}ms", applyResult.requiresRestart(), System.currentTimeMillis() - start);
			removalLifecycle.restartAfterApply(applyResult);
			return ApplyStatus.APPLIED;
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			if (!isCancelledByPlayer()) new ReLauncher(UpdateType.UPDATE, changelogs).restart(preload);
			return ApplyStatus.DEFERRED;
		} catch (Exception e) {
			if (abortedByPlayer(e)) LOGGER.warn("Modpack update apply was aborted by the player", e);
			else ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			return ApplyStatus.FAILED;
		} finally {
			close();
		}
	}

	private void requireLiveConnection() throws IOException {
		if (connectionInfo == null || !connectionInfo.isComplete()) throw new IOException("Modpack connection is unavailable");
		if (downloadClient == null) throw new IOException("Modpack transfer session is unavailable");
	}

	private boolean downloadModpack(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> finalFilesToUpdate, long startFetching, @Nullable FetchManager fetchManager,
			boolean playerFacing) throws InterruptedException {
		int wholeQueue = finalFilesToUpdate.size();

		if (wholeQueue == 0) {
			LOGGER.info("No files to download.");
			return true;
		}

		LOGGER.info("In queue left {} files to download ({})", wholeQueue, ByteFormat.formatSize(totalBytesToDownload));

		if (downloadClient == null) return false;
		if (fetchManager != null) {
			if (fetchManager.isComplete()) LOGGER.info("Third-party sources ready ({} of {} files matched)", fetchManager.resolvedFiles(), fetchManager.totalFiles());
			else LOGGER.info("Downloading from the AutoModpack host without waiting for CurseForge/Modrinth lookup");
		}

		downloadManager = new DownloadManager(totalBytesToDownload, storage, platformCache);
		if (playerFacing) ScreenManager.download(downloadManager, getModpackName());
		downloadManager.attachDownloadClient(downloadClient);

		for (var serverItem : finalFilesToUpdate) {

			String serverFilePath = serverItem.file;
			String serverFileHash = serverItem.sha1;
			long serverFileSize = Long.parseLong(serverItem.size);

			Path downloadFile = storage.activePath(serverFilePath);

			List<DownloadSource> sources = fetchManager == null ? List.of() : fetchManager.sourcesFor(serverFileHash);

			Consumer<DownloadManager.FailureCategory> failureCallback = category -> {
				failedDownloads.put(serverItem, sources.stream().map(DownloadSource::url).toList());
				failedDownloadCategories.put(serverItem, category);
			};

			downloadManager.download(downloadFile, serverFileHash, serverItem.murmur, serverItem.type, sources, serverFileSize, () -> {}, failureCallback);
		}

		downloadManager.joinAll();

		LOGGER.info("Finished downloading files in {}ms", System.currentTimeMillis() - startFetching);

		if (downloadManager.isCancelled()) {
			LOGGER.warn("Download canceled");
			return false;
		}

		downloadManager.finish();
		totalBytesToDownload = 0;

		if (failedDownloads.isEmpty()) return true;
		if (failedDownloadCategories.values().stream().anyMatch(category -> category != DownloadManager.FailureCategory.REMOTE_SOURCE)) {
			LOGGER.error("Object acquisition failed locally; regeneration is not allowed: {}", failedDownloadCategories);
			return false;
		}

		LOGGER.error("Remote object acquisition failed for {}; the advertised generation remains unchanged", failedDownloads.keySet());
		return false;
	}

	// this is run every time we modpack is updated
	private ApplyResult applyPreparedPlan(ReviewedClientPlan<ClientUpdatePlanBuilder.PreparedPlan> reviewed, SelectedModpackTarget target) throws Exception {
		if (!reviewed.review().isApproved()) throw new IllegalStateException("Update plan has not been approved");
		ClientUpdatePlanBuilder.PreparedPlan applied = executePlan(reviewed, target);
		ApplyResult result = RestartDecision.applyResult(applied.plan());
		changelogs.setRestartReasons(result.reasonDescriptions());
		if (result.requiresRestart()) LOGGER.info("Restart required because: {}", String.join(", ", result.reasonDescriptions()));
		return result;
	}

	private void recordChangelogs(ClientUpdatePlanBuilder.PreparedPlan prepared, SelectedModpackTarget target) throws IOException {
		String installedToken = installedToken(target.manifest().modpackId());
		UpdatePreview applied = updatePreview(prepared.plan(), target, installedToken);
		changelogs.replaceWith(applied.withReferences(sourceCatalogue.resolveMainPageReferences(prepared)));
		LOGGER.info("Prepared update changes: {} changed, {} removed", changelogs.changedFiles().size(), changelogs.removedFiles().size());
	}

	/** Acquires all mutable target inputs before creating the plan that the player reviews. */
	private ClientUpdatePlanBuilder.PreparedPlan preparePlanForReview() throws Exception {
		return prepareSelectedPlan(true);
	}

	private ClientUpdatePlanBuilder.PreparedPlan prepareSelectedPlan(boolean playerFacing) throws Exception {
		sourceCatalogue.startSourceFetch();
		try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory())) {
			requireLiveConnection();
			acquireTargetObjects(selectedTarget.flatTarget(), cache, playerFacing);
			planBuilder.reconcileEditableState(cache, selectedTarget.flatTarget());
			return planBuilder.buildPlan(updatePlanInput(true), cache, modCache);
		}
	}

	private PreviewRequestResult requestUpdatePreview() throws Exception {
		if (selectedTarget == null) throw new IllegalStateException("Selected modpack target is unavailable");
		if (isCancelledByPlayer()) return PreviewRequestResult.PREVIEW_NOT_SHOWN;
		ClientUpdatePlanBuilder.PreparedPlan prepared = preparePlanForReview();
		if (isCancelledByPlayer()) return PreviewRequestResult.PREVIEW_NOT_SHOWN;
		ReviewedClientPlan<ClientUpdatePlanBuilder.PreparedPlan> reviewed = new ReviewedClientPlan<>(prepared, ReviewedUpdatePlan.pending(prepared.plan()));
		reviewedUpdatePlan = reviewed;
		if (firstConnection && confirmationState.get() == ConfirmationState.PREVIEWING) {
			reviewed.review().approve();
			if (!confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.STARTED)) return PreviewRequestResult.PREVIEW_NOT_SHOWN;
			return previewResult(applyApprovedPlan(reviewed, System.currentTimeMillis()));
		}
		if (!requiresPlayerReview(prepared, firstConnection)) {
			reviewed.review().approve();
			return previewResult(applyApprovedPlan(reviewed, System.currentTimeMillis()));
		}
		Runnable continueAction = () -> {
			if (!reviewed.review().isApproved()) reviewed.review().approve();
			if (firstConnection && !confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.STARTED)) return;
			startUpdateAfterPreview();
		};
		Runnable cancelAction = firstConnection
				? () -> {
					reviewed.review().cancel();
					confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.WAITING);
				}
				: () -> {
					reviewed.review().cancel();
					close();
				};
		return requestPreparedPlanPreview(prepared, continueAction, cancelAction)
				? PreviewRequestResult.PREVIEW_SHOWN
				: PreviewRequestResult.PREVIEW_NOT_SHOWN;
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
	private boolean requiresReconciliation(ClientUpdatePlanBuilder.PreparedPlan prepared, ModpackJsons.ModpackContentFields installed) throws IOException {
		PackTarget installedTarget = installed == null ? null : PackTarget.fromFlat(installed);
		return UpdateReviewPolicy.requiresPlayerReview(false, installedTarget, prepared.plan().packTarget(), hasPlanImpact(prepared));
	}

	private boolean hasPlanImpact(ClientUpdatePlanBuilder.PreparedPlan prepared) throws IOException {
		UpdatePlan plan = prepared.plan();
		return !plan.operations().isEmpty() || !plan.conflicts().isEmpty() || !plan.preservations().isEmpty() || !plan.baselineCaptures().isEmpty()
				|| !plan.restartReasons().isEmpty() || !Objects.equals(plan.plannedClientConfig(), ClientProjectionView.open(storage).logicalConfig(clientConfig));
	}

	private boolean requestPreparedPlanPreview(ClientUpdatePlanBuilder.PreparedPlan prepared, Runnable continueAction, Runnable cancelAction) throws IOException {
		UpdatePreview preview = updatePreview(prepared.plan(), selectedTarget, installedToken(selectedTarget.manifest().modpackId()))
				.withReferences(sourceCatalogue.resolveMainPageReferences(prepared));
		return ScreenManager.preview(preview, getModpackName(), this,
				(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(continueAction), cancelAction);
	}

	private String installedToken(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		return state != null && modpackId.equals(state.modpackId) ? state.contentToken : "";
	}

	/** Assembles the player-facing preview of one target advance: journal tail, featured notes, and feature manifest. */
	private UpdatePreview updatePreview(UpdatePlan plan, SelectedModpackTarget target, String installedToken) throws IOException {
		List<JournalEntry> journal = new JournalMirror(storage).entries(target.manifest().modpackId());
		return UpdatePreview.create(plan, target.selection(), UpdatePreview.Mode.UPDATE, featuredNotes(journal, installedToken), updateJournal(journal, installedToken))
				.withFeatureManifest(target.manifest());
	}

	/** The journal entries published after the installed state: after the installed token, or the whole journal when it is gone. */
	private static List<JournalEntry> updateJournal(List<JournalEntry> journal, String installedToken) {
		for (int index = journal.size() - 1; index >= 0; index--)
			if (journal.get(index).contentToken().equals(installedToken)) return journal.subList(index + 1, journal.size());
		return journal;
	}

	private static String featuredNotes(List<JournalEntry> journal, String installedToken) {
		List<JournalEntry> range = updateJournal(journal, installedToken);
		return range.isEmpty() ? "" : range.get(range.size() - 1).notes();
	}

	private PreviewRequestResult previewResult(ApplyStatus status) {
		return switch (status) {
			case APPLIED -> PreviewRequestResult.APPLIED;
			case DEFERRED -> PreviewRequestResult.DEFERRED;
			case FAILED -> PreviewRequestResult.FAILED;
		};
	}

	private ClientUpdatePlanBuilder.PreparedPlan executePlan(ReviewedClientPlan<ClientUpdatePlanBuilder.PreparedPlan> reviewed, SelectedModpackTarget target) throws Exception {
		ClientUpdatePlanBuilder.PreparedPlan prepared = reviewed.prepared();
		// An executing plan is a durable fact; a player cancel during the commit must not cancel it afterwards.
		reviewed.review().beginExecution();
		boolean replanned = false;
		while (true) {
			UpdatePlan plan = prepared.plan();
			planBuilder.preparePlanObjects(plan, target.flatTarget());
			UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor().commit(plan, target, prepared.overlayDigest(), prepared.expectedClientConfig());
			if (execution.replanRequired() && !replanned) {
				ensureSelectedModpackUnchanged(prepared);
				try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory())) {
					planBuilder.reconcileEditableState(cache, selectedTarget.flatTarget());
					prepared = planBuilder.buildPlan(updatePlanInput(true), cache, modCache);
				}
				try {
					reviewed.review().requireCompatible(prepared.plan());
				} catch (IllegalStateException e) {
					throw new UpdateReplanRequiredException(execution.blockedPath(), "Mutable input changed the reviewed update consequences", e);
				}
				recordChangelogs(prepared, target);
				replanned = true;
				continue;
			}
			if (!execution.success()) {
				if (execution.replanRequired()) throw new UpdateReplanRequiredException(execution.blockedPath(), execution.message());
				DetachedUpdateHelper.launch();
				throw new UpdateDeferredException(execution.transaction().transactionId, execution.blockedPath(), execution.message());
			}
			reviewed.review().complete();
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
			clientConfig = plan.plannedClientConfig();
			return prepared;
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

	private boolean beginConfirmation() {
		return confirmationState.compareAndSet(ConfirmationState.INACTIVE, ConfirmationState.WAITING);
	}

	private boolean clearPlayerCancel() {
		return playerCancelled.compareAndSet(true, false);
	}

	private void interruptInFlight() {
		sourceCatalogue.cancelIfRunning();
		DownloadManager manager = downloadManager;
		if (manager != null && manager.isRunning()) manager.cancelAllAndShutdown();
	}

	@Override
	public void close() {
		confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.CANCELLED);
		confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.CANCELLED);
		interruptInFlight();
		if (reviewedUpdatePlan != null && reviewedUpdatePlan.review().isApproved()) reviewedUpdatePlan.review().cancel();
		removalLifecycle.cancelPendingReview();
		if (installedSwitchPlan != null && installedSwitchPlan.review().isApproved()) installedSwitchPlan.review().cancel();
		if (!reservedObjectHashes.isEmpty()) {
			reservedObjectHashes.clear();
			try {
				ClientObjectStore.publishOwnership(storage);
			} catch (IOException e) {
				LOGGER.warn("Could not release in-flight CAS ownership; the next startup will refresh it", e);
			}
		}
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

}
