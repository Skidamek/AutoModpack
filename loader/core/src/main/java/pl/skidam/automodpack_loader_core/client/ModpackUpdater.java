package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
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
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.generation.GenerationUpdateRange;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.ModpackContentType;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePlanner;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.update.UpdateReviewPolicy;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_loader_core.DetachedUpdateHelper;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.UpdateTransactionSupport;
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
	private final AtomicReference<ConfirmationState> confirmationState = new AtomicReference<>(ConfirmationState.INACTIVE);
	private final UpdateLoopDetector updateLoopDetector;
	private final ClientStorage storage;
	private final ClientUpdatePlanBuilder planBuilder;
	private volatile FetchManager sourceFetchManager;
	private ClientUpdatePlanBuilder.PreparedPlan installedSwitchPlan;
	private Map<String, UpdatePlan.FileState> firstInstallLocalModFiles = Map.of();
	private Map<String, UpdatePlan.FileState> consentedLocalModFiles = Map.of();
	public record SourceAvailability(int totalFiles, int resolvedFiles, boolean complete, boolean cancelled) {}

	public String getModpackName() {
		return serverModpackContent.modpackName;
	}

	public SelectedModpackTarget getSelectedTarget() {
		return Objects.requireNonNull(selectedTarget, "Selected modpack target is unavailable");
	}

	public String getPatchNotes() {
		return getSelectedTarget().generationRecord().metadata().patchNotes();
	}

	public List<GenerationPatchNoteHistory.Entry> getFirstInstallPatchNotes() {
		return List.of(GenerationPatchNoteHistory.Entry.fromMetadata(getSelectedTarget().generationRecord().metadata()));
	}

	public SourceAvailability getSourceAvailability() {
		FetchManager manager = sourceFetchManager;
		if (manager == null) return new SourceAvailability(0, 0, true, false);
		return new SourceAvailability(manager.totalFiles(), manager.resolvedFiles(), manager.isComplete(), manager.isCancelled());
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
		if (active != null && selectedTarget.manifest().modpackId().equals(active.modpackId)
				&& Objects.equals(selectedTarget.expectedPriorIntent(), selectedTarget.selection().intent()))
			throw new IllegalArgumentException("Installed modpack target and group selection are already active");
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			acquireTargetObjects(selectedTarget.flatTarget(), cache, true);
			ClientUpdatePlanBuilder.PreparedPlan prepared = planBuilder.buildPlan(
					new ClientUpdatePlanBuilder.Input(selectedTarget, selectedTarget.flatTarget(), connectionInfo, clientConfig, true), cache, modCache);
			planBuilder.preparePlanObjects(prepared.plan(), selectedTarget.flatTarget());
			installedSwitchPlan = prepared;
			String installedGenerationId = active != null && selectedTarget.manifest().modpackId().equals(active.modpackId) ? active.generationId : "";
			GenerationUpdateRange updateRange = updateRange(selectedTarget, installedGenerationId);
			return UpdatePreview.create(prepared.plan(), selectedTarget.selection(), UpdatePreview.Mode.UPDATE,
					featuredNotes(updateRange), updateRange.generations()).withFeatureManifest(selectedTarget.manifest());
		}
	}

	/** Applies the last installed-generation switch plan through the normal atomic transaction executor. */
	public void applyInstalledSwitch() throws Exception {
		ClientUpdatePlanBuilder.PreparedPlan prepared = installedSwitchPlan;
		if (prepared == null || selectedTarget == null) throw new IllegalStateException("Installed modpack switch was not prepared");
		try {
			recordChangelogs(prepared, selectedTarget);
			ApplyResult applyResult = applyPreparedPlan(prepared, selectedTarget);
			changelogs.setRestartReasons(applyResult.reasonDescriptions());
			restartAfterApply(applyResult);
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Cached modpack switch transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(UpdateType.SELECT, changelogs).restart(false);
		} finally {
			close();
		}
	}

	/** Returns whether the selected installed target needs an authenticated object-transfer session. */
	public boolean requiresSelectedTargetDownload() throws IOException {
		if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Installed modpack target is unavailable");
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			planBuilder.populateStoreFromCachedLocations(selectedTarget.flatTarget(), cache);
			return !missingTargetObjects(selectedTarget.flatTarget(), cache).isEmpty();
		}
	}

	private ModpackJsons.ModpackContentFields storedTarget() throws IOException {
		SelectedModpackTarget target = storedSelectedTarget();
		return target == null ? null : target.flatTarget();
	}

	private SelectedModpackTarget storedSelectedTarget() throws IOException {
		return new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current()).orElse(null);
	}

	public void selectTarget(SelectionIntent intent) {
		Objects.requireNonNull(intent, "intent");
		SelectedModpackTarget current = getSelectedTarget();
		SelectedModpackTarget replacement = SelectedModpackTarget.prepare(current.completeFields(), current.expectedPriorIntent(), intent, current.platform());
		selectedTarget = replacement;
		serverModpackContent = replacement.flatTarget();
	}

	public ConfirmationState getConfirmationState() {
		return confirmationState.get();
	}

	public void startConfirmedUpdate() {
		if (!confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.PREVIEWING)) return;
		DownloadClient.NET_EXECUTOR.execute(this::startUpdate);
	}

	public void cancelConfirmation() {
		if (!confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.CANCELLED)) return;
		close();
	}

	private void startSourceFetch() throws IOException {
		if (sourceFetchManager != null) return;
		Map<String, FetchManager.FetchData> unique = new LinkedHashMap<>();
		if (selectedTarget != null && selectedTarget.flatTarget().list != null)
			for (var item : selectedTarget.flatTarget().list)
				addSourceFetchData(unique, item.file, item.sha1, item.murmur, item.size, item.type);
		ModpackJsons.ModpackContentFields installed = storedTarget();
		if (installed != null && installed.list != null)
			for (var item : installed.list)
				addSourceFetchData(unique, item.file, item.sha1, item.murmur, item.size, item.type);
		sourceFetchManager = newSourceFetchManager(new ArrayList<>(unique.values()));
	}

	private FetchManager sourceFetch(Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items) {
		Map<String, FetchManager.FetchData> unique = new LinkedHashMap<>();
		for (var item : items) addSourceFetchData(unique, item.file, item.sha1, item.murmur, item.size, item.type);
		List<FetchManager.FetchData> fetchData = new ArrayList<>(unique.values());
		if (fetchData.isEmpty()) return null;
		FetchManager current = sourceFetchManager;
		if (current != null && fetchData.stream().allMatch(item -> current.getFetchDatas().containsKey(item.sha1()))) return current;
		if (current != null) current.cancel();
		sourceFetchManager = newSourceFetchManager(fetchData);
		return sourceFetchManager;
	}

	private FetchManager newSourceFetchManager(List<FetchManager.FetchData> fetchData) {
		if (fetchData.isEmpty()) return null;
		FetchManager manager = new FetchManager(fetchData);
		manager.fetchAsync();
		return manager;
	}

	private static void addSourceFetchData(Map<String, FetchManager.FetchData> unique, String file, String sha1, String murmur, String size, String type) {
		if (!ModpackContentType.isSourceFetchable(type) || sha1 == null || sha1.isBlank()) return;
		unique.putIfAbsent(sha1, new FetchManager.FetchData(file, sha1, murmur, size, type));
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
		this.planBuilder = new ClientUpdatePlanBuilder(this.storage, MODPACK_LOADER, LOADER);
		this.updateLoopDetector = new UpdateLoopDetector(storage.restartLoopStateFile());
		this.downloadClient = downloadClient;
	}

	public void processModpackUpdate(ModpackUtils.UpdateCheckResult result) {
		if (preload) {
			try {
				preloadAcquireTarget();
			} catch (UpdateDeferredException e) {
				LOGGER.warn("Preload transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
				new ReLauncher(UpdateType.UPDATE, changelogs).restart(true);
			} catch (Exception e) {
				LOGGER.error("Failed to preload or apply the selected modpack target; no projection changes were made outside the existing transaction guarantees", e);
			} finally {
				try {
					loadSelectedActiveProjection();
				} catch (Exception e) {
					LOGGER.error("Failed to load the active modpack projection after preload", e);
				}
				close();
			}
			return;
		}

		try {
			requireLiveConnection();

			if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Selected modpack target is unavailable");

			// Handle new modpack
			if (storage.readActiveState() == null || !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) {
				firstConnection = true;
				fullDownload = true;
				firstInstallLocalModFiles = storedTarget() == null ? scanFirstInstallLocalMods() : Map.of();
				startSourceFetch();
				if (!beginConfirmation()) throw new IllegalStateException("Modpack confirmation is already active");
				new ScreenManager().welcome(this);
			} else {
				// Handle existing modpack
				if (result == null) result = ModpackUtils.isUpdate(serverModpackContent, storage);

				startUpdate();
			}
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(UpdateType.UPDATE, changelogs).restart(preload);
			close();
		} catch (Exception e) {
			close();
			new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
		}
	}

	private Map<String, UpdatePlan.FileState> scanFirstInstallLocalMods() throws IOException {
		Path modsDirectory = storage.modsDirectory();
		if (Files.notExists(modsDirectory, LinkOption.NOFOLLOW_LINKS)) return Map.of();
		if (Files.isSymbolicLink(modsDirectory) || !Files.isDirectory(modsDirectory, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Loader-visible mods directory is not a real directory: " + modsDirectory);
		Path loadedMod = THIS_MOD_JAR == null ? null : THIS_MOD_JAR.toAbsolutePath().normalize();
		Map<String, UpdatePlan.FileState> observed = new TreeMap<>();
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); Stream<Path> stream = Files.list(modsDirectory)) {
			for (Path path : stream.sorted().toList()) {
				if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
				Path normalized = path.toAbsolutePath().normalize();
				if (loadedMod != null && normalized.equals(loadedMod)) continue;
				String relative = UpdatePlanner.normalize(storage.gameDirectory().relativize(normalized).toString());
				String hash = cache.rehash(normalized);
				observed.put(relative, new UpdatePlan.FileState(hash, Files.size(normalized), true));
			}
		}
		return Collections.unmodifiableMap(observed);
	}

	private ClientUpdatePlanBuilder.Input updatePlanInput(boolean prepareObjects) {
		Map<String, UpdatePlan.FileState> consent = firstConnection ? consentedLocalModFiles : Map.of();
		return new ClientUpdatePlanBuilder.Input(selectedTarget, selectedTarget.flatTarget(), connectionInfo, clientConfig, prepareObjects, consent);
	}

	private void preloadAcquireTarget() throws Exception {
		if (selectedTarget == null || serverModpackContent == null) {
			LOGGER.info("Skipping modpack preload because no resolved target is available");
			return;
		}
		requireLiveConnection();
		ModpackJsons.ModpackContentFields preloadTarget = selectedTarget.completeTarget();
		long start = System.currentTimeMillis();
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			int downloaded = acquireTargetObjects(preloadTarget, cache, false);
			int targetCount = uniqueObjects(preloadTarget.list == null ? List.of() : preloadTarget.list).size();
			if (downloaded == 0) LOGGER.info("Preload reused all {} verified complete modpack objects", targetCount);
			else LOGGER.info("Preloaded {} complete modpack objects in {}ms", targetCount, System.currentTimeMillis() - start);
		}
		LOGGER.info("Preload acquired the complete selected target; active projection remains unchanged until player review");
	}

	private static Set<ModpackJsons.ModpackContentFields.ModpackContentItem> uniqueObjects(Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items) {
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> unique = new LinkedHashMap<>();
		for (var item : items) unique.putIfAbsent(item.sha1.toLowerCase(Locale.ROOT), item);
		return new LinkedHashSet<>(unique.values());
	}

	private Set<ModpackJsons.ModpackContentFields.ModpackContentItem> missingTargetObjects(ModpackJsons.ModpackContentFields target, FileMetadataCache cache) {
		Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items = target.list == null ? List.of() : target.list;
		return ModpackUtils.identifyUncachedFiles(uniqueObjects(items), cache, storage);
	}

	/** Acquires the complete selected target so every caller uses target state, never a stale generation diff, as its download authority. */
	private int acquireTargetObjects(ModpackJsons.ModpackContentFields target, FileMetadataCache cache, boolean playerFacing) throws Exception {
		Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items = target.list == null ? List.of() : target.list;
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> targetObjects = uniqueObjects(items);
		ModpackUtils.populateStoreFromCWD(targetObjects, cache, storage);
		planBuilder.populateStoreFromCachedLocations(target, cache);
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> missing = ModpackUtils.identifyUncachedFiles(targetObjects, cache, storage);
		if (missing.isEmpty()) return 0;

		requireLiveConnection();
		totalBytesToDownload = missing.stream().mapToLong(item -> Long.parseLong(item.size)).sum();
		FetchManager fetchManager = sourceFetch(missing);
		try {
			if (!downloadModpack(missing, System.currentTimeMillis(), fetchManager, playerFacing))
				throw new IOException("One or more selected modpack objects could not be acquired");
		} catch (Exception e) {
			if (downloadManager != null) downloadManager.cancelAllAndShutdown();
			throw e;
		}

		planBuilder.populateStoreFromActive(target, cache);
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> stillMissing = ModpackUtils.identifyUncachedFiles(targetObjects, cache, storage);
		if (!stillMissing.isEmpty()) throw new IOException("Verified selected-target objects are still missing after acquisition: " + stillMissing.size());
		return missing.size();
	}

	private void loadSelectedActiveProjection() throws Exception {
		if (!Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return;
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null) return;
		if (!ModpackId.isValid(clientConfig.selectedModpackId)) {
			LOGGER.warn("Skipping active modpack load after preload because the configured selected modpack ID is invalid: {}", clientConfig.selectedModpackId);
			return;
		}
		if (!clientConfig.selectedModpackId.equals(state.modpackId)) {
			LOGGER.warn("Skipping active modpack load after preload because active state belongs to {}, but the selected modpack is {}", state.modpackId,
					clientConfig.selectedModpackId);
			return;
		}
		loadModpack();
	}

	public boolean requiresUpdateBeforeLogin(ModpackUtils.UpdateCheckResult result) throws Exception {
		if (result == null || result.requiresUpdate()) return true;
		if (storage.readActiveState() == null || !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return true;
		if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Selected modpack target is unavailable");

		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			ClientUpdatePlanBuilder.PreparedPlan prepared = planBuilder.buildPlan(
					new ClientUpdatePlanBuilder.Input(selectedTarget, selectedTarget.flatTarget(), connectionInfo, clientConfig, false), cache, modCache);
			ModpackJsons.ModpackContentFields installed = storedTarget();
			return requiresReconciliation(prepared, installed);
		}
	}

	// Build the removal plan without changing the installed files.
	public UpdatePreview previewRemoval() throws Exception {
		return previewRemovalLike(UpdatePreview.Mode.REMOVAL);
	}

	public UpdatePreview previewDeactivation() throws Exception {
		return previewRemovalLike(UpdatePreview.Mode.DEACTIVATION);
	}

	private UpdatePreview previewRemovalLike(UpdatePreview.Mode mode) throws Exception {
		ClientUpdatePlanBuilder.RemovalPreparation preparation = planBuilder.prepareRemoval();
		clientConfig = preparation.currentConfig();
		return UpdatePreview.create(preparation.plan(), removalSelection(preparation), mode).withFeatureManifest(removalManifest(preparation));
	}

	public UpdateTransactionExecutor.Execution deactivateModpack() throws Exception {
		return applyRemovalLike(false);
	}

	// Remove the installed modpack and restore baseline files before metadata cleanup.
	public UpdateTransactionExecutor.Execution removeModpack() throws Exception {
		return applyRemovalLike(true);
	}

	private UpdateTransactionExecutor.Execution applyRemovalLike(boolean remove) throws Exception {
		ClientUpdatePlanBuilder.RemovalPreparation preparation = planBuilder.prepareRemoval();
		clientConfig = preparation.currentConfig();
		UpdatePreview applied = UpdatePreview.create(preparation.plan(), removalSelection(preparation),
				remove ? UpdatePreview.Mode.REMOVAL : UpdatePreview.Mode.DEACTIVATION).withFeatureManifest(removalManifest(preparation));
		UpdateTransaction transaction = remove
				? UpdateTransaction.createRemoval(preparation.plan(), ClientPlatform.current(), preparation.expectedPriorIntent(), storage.overlayDigest(preparation.installed().modpackId))
				: UpdateTransaction.createDeactivation(preparation.plan(), ClientPlatform.current(), preparation.expectedPriorIntent(), storage.overlayDigest(preparation.installed().modpackId));
		UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor().commit(transaction);
		if (execution.success()) {
			clientConfig = preparation.plannedConfig();
			if (remove) new ClientGenerationStore(storage).forgetModpack(preparation.installed().modpackId);
			changelogs.replaceWith(applied);
			ApplyResult applyResult = applyResult(preparation.plan());
			changelogs.setRestartReasons(applyResult.reasonDescriptions());
			restartAfterApply(applyResult);
		}
		return execution;
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
		String generationId = preparation.installed().targetGenerationId;
		return new ClientGenerationStore(storage).read(generationId)
				.orElseThrow(() -> new IOException("Installed generation record is unavailable: " + generationId)).manifest();
	}

	// Load the already-installed modpack without contacting the server or
	// reconciling local files against it. Used when update-on-launch is disabled
	// so the user can freely add/remove mods (e.g. a binary search) without
	// AutoModpack restoring or deleting them.
	public void loadModpack() throws Exception {

		if (!Files.exists(storage.activeDirectory())) return;
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			loadModpackMods(cache);
		}
	}

	private void restartAfterApply(ApplyResult applyResult) {
		if (!applyResult.requiresRestart()) {
			updateLoopDetector.clear();
			new ScreenManager().completeWithoutRestart();
			return;
		}
		String fingerprint = updateStateFingerprint(applyResult);
		if (updateLoopDetector.evaluateAndRecord(fingerprint) == UpdateLoopDetector.Decision.SUPPRESS) {
			LOGGER.error("Automatic restart loop detected. AutoModpack already requested two rapid restarts for the same correction state.");
			LOGGER.error("Corrections were applied but still require a restart: {}", String.join(", ", applyResult.reasonDescriptions()));
			LOGGER.error("Another automatic restart was suppressed. The modpack may not be fully active; inspect the surrounding logs and report recurring issues at https://github.com/Skidamek/AutoModpack/issues");
			return;
		}

		UpdateType updateType = applyResult.restartReasons().contains(UpdatePlan.RestartReason.SELECTED_MODPACK)
				? UpdateType.SELECT
				: fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
		new ReLauncher(updateType, changelogs).restart(false);
	}

	private String updateStateFingerprint(ApplyResult applyResult) {
		String generationId;
		try {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			generationId = state == null ? "none" : state.generationId;
		} catch (IOException e) {
			LOGGER.warn("Cannot track rapid modpack restarts because active client state is unavailable", e);
			return null;
		}
		return String.join("\n", storage.activeDirectory().toAbsolutePath().normalize().toString(), generationId, String.join(",", applyResult.reasonIds()));
	}

	// Load the modpack mods that aren't already present in the standard mods
	// directory, without requiring a restart.
	private void loadModpackMods(FileMetadataCache cache) throws Exception {
		if (!preload) {
			LOGGER.info("Modpack is already loaded");
			return;
		}

		Set<String> standardModsHashes;
		List<Path> modpackMods = List.of();

		// 1. Collect hashes of existing standard mods into a Set for fast lookup
		try (Stream<Path> standardModsStream = Files.list(storage.modsDirectory())) {
			standardModsHashes = standardModsStream.filter(JarUtils::isRegularJar) // Check extension/type before
					.map(cache::getHashOrNull) // Safe wrapper for IOException
					.filter(Objects::nonNull).collect(Collectors.toSet()); // Use Set for O(1) performance
		} catch (IOException e) {
			LOGGER.error("Failed to list standard mods directory", e);
			standardModsHashes = Collections.emptySet();
		}

		// 2. Filter modpack mods excluding those already present in standard mods
		Path activeModsDirectory = storage.activePath(ModpackPathPolicy.MODS_ROOT);
		if (Files.exists(activeModsDirectory)) {
			try (Stream<Path> activeMods = Files.list(activeModsDirectory)) {
				final Set<String> finalStandardModsHashes = standardModsHashes;
				modpackMods = activeMods.filter(JarUtils::isRegularJar).filter(mod -> {
					String modHash = cache.getHashOrNull(mod);
					// Only load if hash is valid AND not found in standard set
					return modHash != null && !finalStandardModsHashes.contains(modHash);
				}).toList();
			} catch (IOException e) {
				LOGGER.error("Failed to list modpack mods directory", e);
			}
		}

		MODPACK_LOADER.loadModpack(modpackMods);
	}

	public void startUpdate() {
		try {
			requireLiveConnection();
			new ScreenManager().waiting();
			switch (requestUpdatePreview()) {
				case PREVIEW_SHOWN -> {
					return;
				}
				case APPLIED -> LOGGER.info("Applied an already-authorized no-op update without opening a review screen");
				case DEFERRED -> LOGGER.info("Already-authorized no-op update was deferred to the detached helper");
				case FAILED -> LOGGER.error("Already-authorized no-op update failed; the installed generation was not advanced");
				case PREVIEW_NOT_SHOWN -> LOGGER.warn("Update preview could not be shown; leaving the installed generation unchanged");
			}
			close();
		} catch (Exception e) {
			new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			close();
			return;
		}
	}

	private void startUpdateAfterPreview() {
		long start = System.currentTimeMillis();
		ClientUpdatePlanBuilder.PreparedPlan finalPlan;
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			requireLiveConnection();
			acquireTargetObjects(selectedTarget.flatTarget(), cache, true);
			finalPlan = planBuilder.buildPlan(updatePlanInput(true), cache, modCache);
		} catch (SocketTimeoutException | ConnectException e) {
			new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.connection", FailureCategory.CONNECTION, FailureDestination.CURRENT_SCREEN, null));
			close();
			return;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.info("Interrupted the download");
			close();
			return;
		} catch (Exception e) {
			new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			close();
			return;
		}

		applyApprovedPlan(finalPlan, start);
	}

	private ApplyStatus applyApprovedPlan(ClientUpdatePlanBuilder.PreparedPlan plan, long start) {
		try {
			recordChangelogs(plan, selectedTarget);
			ApplyResult applyResult = applyPreparedPlan(plan, selectedTarget);
			changelogs.setRestartReasons(applyResult.reasonDescriptions());
			LOGGER.info("Update completed! Required restart: {} Took: {}ms", applyResult.requiresRestart(), System.currentTimeMillis() - start);
			restartAfterApply(applyResult);
			return ApplyStatus.APPLIED;
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(UpdateType.UPDATE, changelogs).restart(preload);
			return ApplyStatus.DEFERRED;
		} catch (Exception e) {
			new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			return ApplyStatus.FAILED;
		} finally {
			close();
		}
	}

	private void requireLiveConnection() throws IOException {
		if (connectionInfo == null || !connectionInfo.isComplete()) throw new IOException("Modpack connection is unavailable");
		if (downloadClient == null) throw new IOException("Modpack transfer session is unavailable");
	}

	private boolean downloadModpack(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> finalFilesToUpdate, long startFetching, @Nullable FetchManager fetchManager)
			throws InterruptedException {
		return downloadModpack(finalFilesToUpdate, startFetching, fetchManager, true);
	}

	private boolean downloadModpack(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> finalFilesToUpdate, long startFetching, @Nullable FetchManager fetchManager,
			boolean playerFacing) throws InterruptedException {
		int wholeQueue = finalFilesToUpdate.size();

		if (wholeQueue == 0) {
			LOGGER.info("No files to download.");
			return true;
		}

		LOGGER.info("In queue left {} files to download ({}MB)", wholeQueue, totalBytesToDownload / 1024 / 1024);

		if (downloadClient == null) return false;
		if (fetchManager != null) {
			long fetchStart = System.currentTimeMillis();
			fetchManager.fetch();
			LOGGER.info("Finished resolving third-party sources in {}ms ({} of {} files matched)", System.currentTimeMillis() - fetchStart,
					fetchManager.resolvedFiles(), fetchManager.totalFiles());
		}

		downloadManager = new DownloadManager(totalBytesToDownload, storage);
		if (playerFacing) new ScreenManager().download(downloadManager, getModpackName());
		downloadManager.attachDownloadClient(downloadClient);

		for (var serverItem : finalFilesToUpdate) {

			String serverFilePath = serverItem.file;
			String serverFileHash = serverItem.sha1;
			long serverFileSize = Long.parseLong(serverItem.size);

			Path downloadFile = storage.activePath(serverFilePath);

			List<DownloadSource> sources = new ArrayList<>();
			if (fetchManager != null && fetchManager.getFetchDatas().containsKey(serverFileHash)) {
				sources.addAll(fetchManager.getFetchDatas().get(serverFileHash).fetchedData().sources());
			}

			Consumer<DownloadManager.FailureCategory> failureCallback = category -> {
				failedDownloads.put(serverItem, sources.stream().map(DownloadSource::url).toList());
				failedDownloadCategories.put(serverItem, category);
			};

			downloadManager.download(downloadFile, serverFileHash, sources, serverFileSize, () -> {}, failureCallback);
		}

		downloadManager.joinAll();

		LOGGER.info("Finished downloading files in {}ms", System.currentTimeMillis() - startFetching);

		if (downloadManager.isCancelled()) {
			LOGGER.warn("Download canceled");
			return false;
		}

		downloadManager.cancelAllAndShutdown();
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
	private ApplyResult applyPreparedPlan(ClientUpdatePlanBuilder.PreparedPlan prepared, SelectedModpackTarget target) throws Exception {
		executePlan(prepared, target);
		ApplyResult result = applyResult(prepared.plan());
		changelogs.setRestartReasons(result.reasonDescriptions());
		if (result.requiresRestart()) LOGGER.info("Restart required because: {}", String.join(", ", result.reasonDescriptions()));
		return result;
	}

	private static ApplyResult applyResult(UpdatePlan plan) {
		return new ApplyResult(plan.restartReasons());
	}

	private void recordChangelogs(ClientUpdatePlanBuilder.PreparedPlan prepared, SelectedModpackTarget target) throws IOException {
		GenerationUpdateRange updateRange = updateRange(target, installedGenerationId(target.manifest().modpackId()));
		UpdatePreview applied = UpdatePreview.create(prepared.plan(), target.selection(), UpdatePreview.Mode.UPDATE,
				featuredNotes(updateRange), updateRange.generations()).withFeatureManifest(target.manifest());
		changelogs.replaceWith(applied.withReferences(resolveMainPageReferences(prepared)));
		LOGGER.info("Prepared update changes: {} changed, {} removed", changelogs.changedFiles().size(), changelogs.removedFiles().size());
	}

	private ChangeSet.ReferenceProvider resolveMainPageReferences(ClientUpdatePlanBuilder.PreparedPlan prepared) {
		FetchManager manager = sourceFetchManager;
		if (manager == null) return (location, path) -> List.of();
		manager.fetch();
		Map<UpdatePlan.FileKey, String> hashes = new LinkedHashMap<>();
		for (UpdatePlan.Operation operation : prepared.plan().operations()) {
			UpdatePlan.FileKey file = new UpdatePlan.FileKey(operation.root(), operation.relativePath());
			if (operation.operation() == UpdatePlan.OperationType.INSTALL_OBJECT && operation.expectedObjectHash() != null) {
				hashes.put(file, operation.expectedObjectHash());
			} else if (operation.operation() == UpdatePlan.OperationType.DELETE) {
				UpdatePlan.FileState original = prepared.originalFiles().get(file);
				if (original != null && original.sha1() != null) hashes.put(file, original.sha1());
			}
		}
		Map<UpdatePlan.FileKey, List<String>> resolved = new LinkedHashMap<>();
		for (var entry : hashes.entrySet()) {
			FetchManager.Datas data = manager.getFetchDatas().get(entry.getValue());
			if (data == null || data.fetchedData().mainPageUrls().isEmpty()) continue;
			resolved.put(entry.getKey(), List.copyOf(data.fetchedData().mainPageUrls()));
		}
		Map<UpdatePlan.FileKey, List<String>> references = Map.copyOf(resolved);
		return (location, path) -> {
			try {
				return references.getOrDefault(new UpdatePlan.FileKey(UpdatePlan.Root.valueOf(location), path), List.of());
			} catch (IllegalArgumentException e) {
				return List.of();
			}
		};
	}

	private PreviewRequestResult requestUpdatePreview() throws Exception {
		if (selectedTarget == null) throw new IllegalStateException("Selected modpack target is unavailable");
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			ClientUpdatePlanBuilder.PreparedPlan prepared = planBuilder.buildPlan(updatePlanInput(false), cache, modCache);
			if (!requiresPlayerReview(prepared, firstConnection)) {
				return switch (applyApprovedPlan(prepared, System.currentTimeMillis())) {
					case APPLIED -> PreviewRequestResult.APPLIED;
					case DEFERRED -> PreviewRequestResult.DEFERRED;
					case FAILED -> PreviewRequestResult.FAILED;
				};
			}
			startSourceFetch();
			Runnable continueAction = () -> {
				if (firstConnection && !confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.STARTED)) return;
				startUpdateAfterPreview();
			};
			Runnable cancelAction = firstConnection
					? () -> confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.WAITING)
					: this::close;
			return requestPreparedPlanPreview(prepared, continueAction, cancelAction, firstConnection)
					? PreviewRequestResult.PREVIEW_SHOWN
					: PreviewRequestResult.PREVIEW_NOT_SHOWN;
		}
	}

	/** A review is required for first install, a changed generation identity, or any plan impact. */
	private boolean requiresPlayerReview(ClientUpdatePlanBuilder.PreparedPlan prepared, boolean firstInstall) throws IOException {
		ModpackJsons.ModpackContentFields installed = storedTarget();
		GenerationTarget installedTarget = installed == null ? null : GenerationTarget.fromFlat(installed);
		return UpdateReviewPolicy.requiresPlayerReview(firstInstall, installedTarget, prepared.plan().generationTarget(), hasPlanImpact(prepared));
	}

	/** Login reconciliation must also advance a newly advertised generation, even when its files are unchanged. */
	private boolean requiresReconciliation(ClientUpdatePlanBuilder.PreparedPlan prepared, ModpackJsons.ModpackContentFields installed) {
		GenerationTarget installedTarget = installed == null ? null : GenerationTarget.fromFlat(installed);
		return UpdateReviewPolicy.requiresPlayerReview(false, installedTarget, prepared.plan().generationTarget(), hasPlanImpact(prepared));
	}

	private boolean hasPlanImpact(ClientUpdatePlanBuilder.PreparedPlan prepared) {
		UpdatePlan plan = prepared.plan();
		return !plan.operations().isEmpty() || !plan.conflicts().isEmpty() || !plan.preservations().isEmpty() || !plan.baselineCaptures().isEmpty()
				|| !plan.restartReasons().isEmpty() || !ConfigTools.GSON.toJson(plan.plannedClientConfig()).equals(ConfigTools.GSON.toJson(clientConfig));
	}

	private boolean requestPreparedPlanPreview(ClientUpdatePlanBuilder.PreparedPlan prepared, Runnable continueAction, Runnable cancelAction, boolean returnToSelection) throws IOException {
		GenerationUpdateRange updateRange = updateRange(selectedTarget, installedGenerationId(selectedTarget.manifest().modpackId()));
		UpdatePreview preview = UpdatePreview.create(prepared.plan(), selectedTarget.selection(), UpdatePreview.Mode.UPDATE,
				featuredNotes(updateRange), updateRange.generations()).withFeatureManifest(selectedTarget.manifest()).withReferences(resolveMainPageReferences(prepared));
		return new ScreenManager().preview(preview, getModpackName(),
				(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(continueAction), cancelAction, returnToSelection);
	}

	private String installedGenerationId(String modpackId) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		return state != null && modpackId.equals(state.modpackId) ? state.generationId : "";
	}

	private static GenerationUpdateRange updateRange(SelectedModpackTarget target, String installedGenerationId) {
		return GenerationUpdateRange.between(target.patchNotesHistory(), installedGenerationId, target.generationRecord().metadata().generationId());
	}

	private static String featuredNotes(GenerationUpdateRange updateRange) {
		return updateRange.featuredNotes().map(GenerationPatchNoteHistory.Entry::patchNotes).orElse("");
	}

	private void executePlan(ClientUpdatePlanBuilder.PreparedPlan prepared, SelectedModpackTarget target) throws IOException {
		UpdatePlan plan = prepared.plan();
		planBuilder.preparePlanObjects(plan, target.flatTarget());
		UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor().commit(plan, target, prepared.overlayDigest());
		if (!execution.success()) {
			DetachedUpdateHelper.launch(execution.transaction());
			throw new UpdateDeferredException(execution.transaction().transactionId, execution.blockedPath(), execution.message());
		}
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
	}

	private void cleanupOverlayState(UpdatePlan plan, String modpackId) throws IOException {
		Set<String> deletedPaths = new TreeSet<>(storage.readOverlayState(modpackId).deletedPaths);
		for (UpdatePlan.Operation operation : plan.operations())
			if (operation.root() == UpdatePlan.Root.OVERLAY && operation.operation() == UpdatePlan.OperationType.DELETE)
				deletedPaths.remove(UpdatePlanner.normalize(operation.relativePath()));
		storage.writeOverlayState(modpackId, deletedPaths);
	}

	private boolean beginConfirmation() {
		return confirmationState.compareAndSet(ConfirmationState.INACTIVE, ConfirmationState.WAITING);
	}

	@Override
	public void close() {
		confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.CANCELLED);
		confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.CANCELLED);
		FetchManager sourceFetch = sourceFetchManager;
		if (sourceFetch != null && !sourceFetch.isComplete()) sourceFetch.cancel();
		DownloadManager manager = downloadManager;
		if (manager != null && manager.isRunning()) manager.cancelAllAndShutdown();
		if (closed.compareAndSet(false, true) && downloadClient != null) downloadClient.close();
	}

	public enum ConfirmationState {
		INACTIVE, WAITING, PREVIEWING, STARTED, CANCELLED
	}

	private record ApplyResult(Set<UpdatePlan.RestartReason> restartReasons) {
		private ApplyResult {
			restartReasons = restartReasons.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(restartReasons));
		}

		private boolean requiresRestart() {
			return !restartReasons.isEmpty();
		}

		private List<String> reasonIds() {
			return restartReasons.stream().map(Enum::name).toList();
		}

		private List<String> reasonDescriptions() {
			return restartReasons.stream().map(ModpackUpdater::describeRestartReason).toList();
		}
	}

	private static String describeRestartReason(UpdatePlan.RestartReason reason) {
		return switch (reason) {
			case REMOVED_NON_MODPACK_FILES -> "files removed from the modpack were deleted from the game directory";
			case REMOVED_LOCAL_MODS -> "player-approved local mods were preserved and removed from the game directory";
			case CORRECTED_FILE_LOCATIONS -> "standard-directory mods were copied or updated";
			case FIXED_NESTED_MODS -> "conflicting nested mods were copied to the standard mods directory";
			case REMOVED_DUPLICATE_MODS -> "duplicate standard-directory mods were removed";
			case REMOVED_STANDARD_MODS -> "modpack-owned mods were removed from the standard mods directory";
			case APPLIED_SERVER_DELETIONS -> "server-requested mod deletions were applied";
			case CHANGED_LOADER_VERSION -> "launcher loader-version metadata changed";
			case CHANGED_GROUP_SELECTION -> "the selected modpack groups changed";
			case SELECTED_MODPACK -> "the selected stable modpack changed";
		};
	}

	private enum ApplyStatus {
		APPLIED, DEFERRED, FAILED
	}

	private enum PreviewRequestResult {
		PREVIEW_SHOWN, PREVIEW_NOT_SHOWN, APPLIED, DEFERRED, FAILED
	}

}
