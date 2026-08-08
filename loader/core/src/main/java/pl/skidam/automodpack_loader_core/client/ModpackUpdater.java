package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
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
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientOverlaySnapshot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.RecoveryArchive;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePlanner;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_core.utils.launchers.LauncherVersionSwapper;
import pl.skidam.automodpack_loader_core.DetachedUpdateHelper;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.UpdateTransactionSupport;
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
	private Jsons.ModpackContentFields serverModpackContent;
	private final Map<Jsons.ModpackContentFields.ModpackContentItem, List<String>> failedDownloads = new ConcurrentHashMap<>();
	private final Map<Jsons.ModpackContentFields.ModpackContentItem, DownloadManager.FailureCategory> failedDownloadCategories = new ConcurrentHashMap<>();
	private final Jsons.ConnectionInfo connectionInfo;
	private final DownloadClient downloadClient;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicReference<ConfirmationState> confirmationState = new AtomicReference<>(ConfirmationState.INACTIVE);
	private final UpdateLoopDetector updateLoopDetector;
	private final ClientStorage storage;
	private volatile FetchManager sourceFetchManager;
	private PreparedPlan cachedSwitchPlan;
	private static final Comparator<RecoveryFile> RECOVERY_FILE_ORDER = Comparator.comparing(RecoveryFile::logicalPath).thenComparing(RecoveryFile::sha1)
			.thenComparingLong(RecoveryFile::size);

	public record RecoveryFile(String logicalPath, String sha1, long size, String sourceGenerationId, String preservedAt) {
		public RecoveryFile(String logicalPath, String sha1, long size) {
			this(logicalPath, sha1, size, "", "");
		}

		public RecoveryFile {
			logicalPath = UpdatePlanner.normalize(logicalPath);
			if (sha1 == null || !sha1.matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException("Recovery file SHA-1 is invalid");
			sha1 = sha1.toLowerCase(Locale.ROOT);
			if (size < 0) throw new IllegalArgumentException("Recovery file size is invalid");
			sourceGenerationId = sourceGenerationId == null ? "" : sourceGenerationId;
			if (!sourceGenerationId.isEmpty() && !sourceGenerationId.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Recovery source generation ID is invalid");
			preservedAt = preservedAt == null ? "" : preservedAt;
			if (!preservedAt.isEmpty()) Instant.parse(preservedAt);
		}
	}

	public record RecoverySnapshot(List<RecoveryFile> archived, List<RecoveryFile> available) {
		public RecoverySnapshot {
			archived = List.copyOf(archived);
			available = List.copyOf(available);
		}
	}

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

	public SourceAvailability getSourceAvailability() {
		FetchManager manager = sourceFetchManager;
		if (manager == null) return new SourceAvailability(0, 0, true, false);
		return new SourceAvailability(manager.totalFiles(), manager.resolvedFiles(), manager.isComplete(), manager.isCancelled());
	}

	/** Builds a reviewable switch plan using only the stored generation and verified local objects. */
	public UpdatePreview previewCachedSwitch() throws Exception {
		if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Cached modpack target is unavailable");
		Jsons.ClientGenerationStateFields active = storage.readActiveState();
		if (active != null && selectedTarget.manifest().modpackId().equals(active.modpackId)) throw new IllegalArgumentException("Cached switch target is already active");
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			populateStoreFromCachedLocations(selectedTarget.flatTarget(), cache);
			PreparedPlan prepared = buildPlan(cache, modCache, selectedTarget.flatTarget(), true);
			ensurePlanObjects(prepared.plan(), selectedTarget.flatTarget());
			cachedSwitchPlan = prepared;
			List<GenerationPatchNoteHistory.Entry> missedPatchNotes = GenerationPatchNoteHistory.after(selectedTarget.patchNotesHistory(), "");
			return UpdatePreview.create(prepared.plan(), prepared.originalFiles(), selectedTarget.flatTarget(), selectedTarget.selection(), false, null,
					selectedTarget.generationRecord().metadata().patchNotes(), missedPatchNotes);
		}
	}

	/** Applies the last cached switch plan through the normal atomic transaction executor. */
	public void applyCachedSwitch() throws Exception {
		PreparedPlan prepared = cachedSwitchPlan;
		if (prepared == null || selectedTarget == null) throw new IllegalStateException("Cached switch was not prepared");
		try {
			recordChangelogs(prepared, selectedTarget);
			ApplyResult applyResult = applyPreparedPlan(prepared, selectedTarget);
			changelogs.setRestartReasons(applyResult.reasonDescriptions());
			restartAfterApply(applyResult);
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Cached modpack switch transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(storage.activeDirectory(), UpdateType.SELECT, changelogs).restart(false);
		} finally {
			close();
		}
	}

	public Set<Jsons.ModpackContentFields.ModpackContentItem> getModpackFileList() {
		return serverModpackContent.list;
	}

	private Jsons.ModpackContentFields storedTarget() throws IOException {
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
		DownloadClient.NET_EXECUTOR.execute(() -> startUpdate(getModpackFileList()));
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
		Jsons.ModpackContentFields installed = storedTarget();
		if (installed != null && installed.list != null)
			for (var item : installed.list)
				addSourceFetchData(unique, item.file, item.sha1, item.murmur, item.size, item.type);
		sourceFetchManager = newSourceFetchManager(new ArrayList<>(unique.values()));
	}

	private FetchManager ensureSourceFetch(Collection<Jsons.ModpackContentFields.ModpackContentItem> items) {
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
		if (!isSourceFetchType(type) || sha1 == null || sha1.isBlank()) return;
		unique.putIfAbsent(sha1, new FetchManager.FetchData(file, sha1, murmur, size, type));
	}

	private static boolean isSourceFetchType(String type) {
		return "mod".equals(type) || "shader".equals(type) || "resourcepack".equals(type);
	}

	public ModpackUpdater(SelectedModpackTarget selectedTarget, Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret, ClientStorage storage) {
		this(selectedTarget, connectionInfo, secret, storage, null);
	}

	public ModpackUpdater(Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret, ClientStorage storage) {
		this(null, connectionInfo, secret, storage, null);
	}

	public ModpackUpdater(SelectedModpackTarget selectedTarget, Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret, ClientStorage storage,
			DownloadClient downloadClient) {
		this.selectedTarget = selectedTarget;
		this.serverModpackContent = selectedTarget == null ? null : selectedTarget.flatTarget();
		this.connectionInfo = connectionInfo;
		this.storage = Objects.requireNonNull(storage, "storage");
		this.updateLoopDetector = new UpdateLoopDetector(storage.restartLoopStateFile());
		this.downloadClient = downloadClient;
	}

	public void processModpackUpdate(ModpackUtils.UpdateCheckResult result) {
		if (preload) {
			try {
				preloadAcquireTarget();
			} catch (Exception e) {
				LOGGER.error("Failed to preload the selected modpack objects; keeping the active projection unchanged", e);
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
				startSourceFetch();
				if (!beginConfirmation()) throw new IllegalStateException("Modpack confirmation is already active");
				new ScreenManager().welcome(this);
			} else {
				// Handle existing modpack
				if (result == null) result = ModpackUtils.isUpdate(serverModpackContent, storage);

				startUpdate(result.filesToUpdate());
			}
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(storage.activeDirectory(), UpdateType.UPDATE, changelogs).restart(preload);
			close();
		} catch (Exception e) {
			LOGGER.error("Error while initializing modpack updater", e);
			close();
		}
	}

	private void preloadAcquireTarget() throws Exception {
		if (selectedTarget == null || serverModpackContent == null) {
			LOGGER.info("Skipping modpack preload because no resolved target is available");
			return;
		}
		requireLiveConnection();
		Jsons.ModpackContentFields preloadTarget = selectedTarget.completeTarget();
		Collection<Jsons.ModpackContentFields.ModpackContentItem> targetItems = preloadTarget.list == null ? List.of() : preloadTarget.list;
		if (targetItems.isEmpty()) {
			LOGGER.info("Selected modpack target contains no files; preload has nothing to acquire");
			return;
		}

		long start = System.currentTimeMillis();
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			Set<Jsons.ModpackContentFields.ModpackContentItem> allTargetItems = new LinkedHashSet<>(targetItems);
			ModpackUtils.populateStoreFromCWD(allTargetItems, cache, storage);
			populateStoreFromActive(preloadTarget, cache);
			Set<Jsons.ModpackContentFields.ModpackContentItem> targetSet = uniqueObjects(allTargetItems);
			Set<Jsons.ModpackContentFields.ModpackContentItem> uncached = ModpackUtils.identifyUncachedFiles(targetSet, cache, storage);
			if (uncached.isEmpty()) {
				LOGGER.info("Preload reused all {} verified complete modpack objects", targetSet.size());
				return;
			}

			totalBytesToDownload = uncached.stream().mapToLong(item -> Long.parseLong(item.size)).sum();
			FetchManager fetchManager = ensureSourceFetch(uncached);
			if (!downloadModpack(uncached, start, fetchManager, false)) throw new IOException("One or more selected modpack objects could not be acquired");
			Set<Jsons.ModpackContentFields.ModpackContentItem> stillUncached = ModpackUtils.identifyUncachedFiles(targetSet, cache, storage);
			if (!stillUncached.isEmpty()) throw new IOException("Verified CAS objects are still missing after preload: " + stillUncached.size());
			LOGGER.info("Preloaded {} complete modpack objects in {}ms", targetSet.size(), System.currentTimeMillis() - start);
		}
	}

	private static Set<Jsons.ModpackContentFields.ModpackContentItem> uniqueObjects(Collection<Jsons.ModpackContentFields.ModpackContentItem> items) {
		Map<String, Jsons.ModpackContentFields.ModpackContentItem> unique = new LinkedHashMap<>();
		for (var item : items) unique.putIfAbsent(item.sha1.toLowerCase(Locale.ROOT), item);
		return new LinkedHashSet<>(unique.values());
	}

	private void loadSelectedActiveProjection() throws Exception {
		if (!Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return;
		Jsons.ClientGenerationStateFields state = storage.readActiveState();
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
			PreparedPlan prepared = buildPlan(cache, modCache, selectedTarget.flatTarget(), false);
			Jsons.ModpackContentFields installed = storedTarget();
			return requiresReconciliation(prepared, installed);
		}
	}

	// Build the removal plan without changing the installed files.
	public UpdatePreview previewRemoval() throws Exception {
		RemovalPreparation preparation = prepareRemoval();
		return UpdatePreview.create(preparation.plan(), preparation.files(), preparation.installed(), removalSelection(preparation), true, preparation.baseline(),
				preparation.completeFields().generation == null ? "" : preparation.completeFields().generation.patchNotes);
	}

	// Remove the installed modpack and restore baseline files before metadata cleanup.
	public UpdateTransactionExecutor.Execution removeModpack() throws Exception {
		RemovalPreparation preparation = prepareRemoval();
		UpdateTransaction transaction = UpdateTransaction.createRemoval(preparation.plan(), ClientPlatform.current(), preparation.expectedPriorIntent(), storage.overlayDigest(preparation.installed().modpackId));
		UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor().commit(transaction);
		if (execution.success()) {
			clientConfig = preparation.plannedConfig();
			try {
				storage.clearOverlay(preparation.installed().modpackId);
			} catch (IOException e) {
				LOGGER.warn("Modpack removal committed, but its editable overlay could not be removed", e);
			}
		}
		return execution;
	}

	private static ResolvedSelection removalSelection(RemovalPreparation preparation) {
		SelectionIntent intent = preparation.expectedPriorIntent();
		if (intent == null) return null;
		Set<String> selected = preparation.installed().selectedGroups == null ? Set.of() : preparation.installed().selectedGroups;
		Set<String> stale = new TreeSet<>(intent.requestedGroups());
		stale.removeAll(selected);
		return new ResolvedSelection(intent, new TreeSet<>(selected), new TreeSet<>(stale));
	}

	private RemovalPreparation prepareRemoval() throws Exception {
		Jsons.ModpackContentFields installed = storedTarget();
		Jsons.CompleteModpackContentFields completeFields = new ClientGenerationStore(storage).read(storage.readActiveState().generationId)
				.orElseThrow(() -> new IOException("Active client generation record is missing")).toFields();
		Jsons.ClientBaselineFields baseline = ConfigTools.read(storage.baselineFile(installed.modpackId), Jsons.ClientBaselineFields.class)
				.orElseGet(() -> {
					Jsons.ClientBaselineFields empty = new Jsons.ClientBaselineFields();
					empty.modpackId = installed.modpackId;
					return empty;
				});
		Jsons.ClientConfigFieldsV3 currentConfig = ConfigTools.read(storage.clientConfigFile(), Jsons.ClientConfigFieldsV3.class)
				.orElseGet(Jsons.ClientConfigFieldsV3::new);
		Jsons.ClientConfigFieldsV3 plannedConfig = new Jsons.ClientConfigFieldsV3(currentConfig);
		if (installed.modpackId.equals(plannedConfig.selectedModpackId)) plannedConfig.selectedModpackId = "";
		clientConfig = currentConfig;

		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(installed, installed, null, cache,
					Map.of(installed.modpackId, storage.overlaySnapshot(installed.modpackId, cache)));
			Set<String> availableBaselineObjects = new HashSet<>();
			if (baseline.entries != null) for (var entry : baseline.entries) {
				if (entry == null || entry.absent || entry.objectHash == null || entry.size < 0) continue;
				String hash = entry.objectHash.toLowerCase(Locale.ROOT);
				if (SmartFileUtils.isValidFile(storage.objectsDirectory().resolve(hash), entry.size, hash)) availableBaselineObjects.add(hash);
			}
			UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, availableBaselineObjects, plannedConfig));
			SelectionIntent expectedPriorIntent = new ClientSelectionStore(storage.selectionFile()).get(installed.modpackId).orElse(null);
			return new RemovalPreparation(plan, completeFields, installed, baseline, expectedPriorIntent, plannedConfig, files);
		}
	}

	// Load the already-installed modpack without contacting the server or
	// reconciling local files against it. Used when update-on-launch is disabled
	// so the user can freely add/remove mods (e.g. a binary search) without
	// AutoModpack restoring or deleting them.
	public Path recoverDeletedFile(String logicalPath, String sha1, long size) throws IOException {
		Jsons.ModpackContentFields installed = storedTarget();
		String normalizedPath = UpdatePlanner.normalize(logicalPath);
		if (UpdatePlanner.managedCleanupKey(normalizedPath).isEmpty()) throw new IOException("Recovery path is not managed: " + normalizedPath);
		OwnershipLedger ledger = OwnershipLedger.fromFields(installed.ownershipLedger);
		OwnershipLedger.Entry entry = ledger.entries().get(normalizedPath);
		String normalizedHash = sha1 == null ? null : sha1.toLowerCase(Locale.ROOT);
		if (entry == null || normalizedHash == null || !entry.historicalHashes().contains(new OwnershipLedger.Content(normalizedHash, size)))
			throw new IOException("Recovery object is not owned by the installed modpack ledger");
		Path archiveDirectory = recoveryDirectory(installed.modpackId);
		return RecoveryArchive.archive(storage.objectsDirectory(), archiveDirectory, normalizedPath, normalizedHash, size,
				entry.lastPublishedGenerationId(), Instant.now().toString());
	}

	public RecoverySnapshot recoverySnapshot() throws IOException {
		Jsons.ModpackContentFields installed = storedTarget();
		Jsons.ClientRecoveryArchiveFields archive = RecoveryArchive.read(recoveryDirectory(installed.modpackId));
		List<RecoveryFile> archived = new ArrayList<>();
		for (var entry : archive.entries) archived.add(new RecoveryFile(entry.logicalPath, entry.sha1, entry.size, entry.sourceGenerationId, entry.preservedAt));
		archived.sort(RECOVERY_FILE_ORDER);
		Set<String> archivedKeys = archived.stream().map(ModpackUpdater::recoveryKey).collect(Collectors.toSet());
		Set<String> targetPaths = new HashSet<>();
		if (installed.list != null) for (var item : installed.list) targetPaths.add(UpdatePlanner.normalize(item.file));
		OwnershipLedger ledger = OwnershipLedger.fromFields(installed.ownershipLedger);
		List<RecoveryFile> available = new ArrayList<>();
		Path storeRoot = storage.objectsDirectory();
		for (OwnershipLedger.Entry ledgerEntry : ledger.entries().values()) {
			if (targetPaths.contains(ledgerEntry.logicalPath()) || UpdatePlanner.managedCleanupKey(ledgerEntry.logicalPath()).isEmpty()) continue;
			for (OwnershipLedger.Content content : ledgerEntry.historicalHashes()) {
				String hash = content.sha1().toLowerCase(Locale.ROOT);
				if (!SmartFileUtils.isValidFile(storeRoot.resolve(hash), content.size(), hash)) continue;
				RecoveryFile file = new RecoveryFile(ledgerEntry.logicalPath(), hash, content.size(), ledgerEntry.lastPublishedGenerationId(), "");
				if (!archivedKeys.contains(recoveryKey(file))) available.add(file);
			}
		}
		available.sort(RECOVERY_FILE_ORDER);
		return new RecoverySnapshot(archived, available);
	}

	private static String recoveryKey(RecoveryFile file) {
		return file.logicalPath() + "|" + file.sha1() + "|" + file.size();
	}

	private Path recoveryDirectory(String modpackId) throws IOException {
		Path archiveDirectory = storage.recoveryDirectory(modpackId);
		if (Files.isSymbolicLink(storage.recoveryDirectory(modpackId)) || Files.isSymbolicLink(archiveDirectory))
			throw new IOException("Recovery archive directory may not be a symbolic link");
		return archiveDirectory;
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
			return;
		}
		String fingerprint = updateStateFingerprint(applyResult);
		if (updateLoopDetector.evaluateAndRecord(fingerprint) == UpdateLoopDetector.Decision.SUPPRESS) {
			LOGGER.error("Automatic restart loop detected. AutoModpack already requested two rapid restarts for the same correction state.");
			LOGGER.error("Corrections were applied but still require a restart: {}", String.join(", ", applyResult.reasonDescriptions()));
			LOGGER.error("Another automatic restart was suppressed. The modpack may not be fully active; inspect the surrounding logs and report recurring issues at https://github.com/Skidamek/AutoModpack/issues");
			return;
		}

		UpdateType updateType = applyResult.restartReasons().contains(RestartReason.SELECTED_MODPACK)
				? UpdateType.SELECT
				: fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
		new ReLauncher(storage.activeDirectory(), updateType, changelogs).restart(false);
	}

	private String updateStateFingerprint(ApplyResult applyResult) {
		String generationId;
		try {
			Jsons.ClientGenerationStateFields state = storage.readActiveState();
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
			standardModsHashes = standardModsStream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".jar")) // Check extension/type before
					.map(cache::getHashOrNull) // Safe wrapper for IOException
					.filter(Objects::nonNull).collect(Collectors.toSet()); // Use Set for O(1) performance
		} catch (IOException e) {
			LOGGER.error("Failed to list standard mods directory", e);
			standardModsHashes = Collections.emptySet();
		}

		// 2. Filter modpack mods excluding those already present in standard mods
		Path activeModsDirectory = storage.activePath("mods");
		if (Files.exists(activeModsDirectory)) {
			try (Stream<Path> activeMods = Files.list(activeModsDirectory)) {
				final Set<String> finalStandardModsHashes = standardModsHashes;
				modpackMods = activeMods.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".jar")).filter(mod -> {
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

	public void startUpdate(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate) {
		try {
			requireLiveConnection();
			new ScreenManager().waiting();
			switch (requestUpdatePreview(filesToUpdate)) {
				case PREVIEW_SHOWN -> {
					return;
				}
				case APPLIED -> LOGGER.info("Applied a metadata-only generation update without opening a review screen");
				case DEFERRED -> LOGGER.info("Metadata-only generation update was deferred to the detached helper");
				case FAILED -> LOGGER.error("Metadata-only generation update failed; the installed generation was not advanced");
				case PREVIEW_NOT_SHOWN -> LOGGER.warn("Update preview could not be shown; leaving the installed generation unchanged");
			}
			close();
		} catch (Exception e) {
			new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
			LOGGER.error("Failed to prepare the modpack update preview", e);
			close();
			return;
		}
	}

	private void startUpdateAfterPreview(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate) {
		long start = System.currentTimeMillis();
		PreparedPlan finalPlan;
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			requireLiveConnection();
			// Don't download files which already exist
			ModpackUtils.populateStoreFromCWD(filesToUpdate, cache, storage);
			var finalFilesToUpdate = ModpackUtils.identifyUncachedFiles(filesToUpdate, cache, storage);

			long startFetching = System.currentTimeMillis();
			for (Jsons.ModpackContentFields.ModpackContentItem serverItem : finalFilesToUpdate) totalBytesToDownload += Long.parseLong(serverItem.size);
			FetchManager fetchManager = ensureSourceFetch(finalFilesToUpdate);

			// DOWNLOAD
			try {
				if (!downloadModpack(finalFilesToUpdate, startFetching, fetchManager)) {
					reportFailedDownloads(start);
					close();
					return;
				}
			} catch (Exception e) {
				if (downloadManager != null) downloadManager.cancelAllAndShutdown();
				throw e;
			}

			finalPlan = buildPlan(cache, modCache, selectedTarget.flatTarget());
		} catch (SocketTimeoutException | ConnectException e) {
			String host = connectionInfo == null || connectionInfo.endpoint == null ? "modpack host" : "Modpack host of " + connectionInfo.endpoint.getHostString();
			LOGGER.error("{} is not responding", host, e);
			new ScreenManager().error("automodpack.error.critical", host + " is not responding", "automodpack.error.logs");
			close();
			return;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.info("Interrupted the download");
			close();
			return;
		} catch (Exception e) {
			new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
			LOGGER.error("Critical error while acquiring modpack objects", e);
			close();
			return;
		}

		applyApprovedPlan(finalPlan, start);
	}

	private ApplyStatus applyApprovedPlan(PreparedPlan plan, long start) {
		try {
			recordChangelogs(plan, selectedTarget);
			ApplyResult applyResult = applyPreparedPlan(plan, selectedTarget);
			changelogs.setRestartReasons(applyResult.reasonDescriptions());
			LOGGER.info("Update completed! Required restart: {} Took: {}ms", applyResult.requiresRestart(), System.currentTimeMillis() - start);
			restartAfterApply(applyResult);
			return ApplyStatus.APPLIED;
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(storage.activeDirectory(), UpdateType.UPDATE, changelogs).restart(preload);
			return ApplyStatus.DEFERRED;
		} catch (Exception e) {
			new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
			LOGGER.error("Critical error while applying the modpack update", e);
			return ApplyStatus.FAILED;
		} finally {
			close();
		}
	}

	private void requireLiveConnection() throws IOException {
		if (connectionInfo == null || !connectionInfo.isComplete()) throw new IOException("Modpack connection is unavailable");
		if (downloadClient == null) throw new IOException("Modpack transfer session is unavailable");
	}

	private boolean downloadModpack(Set<Jsons.ModpackContentFields.ModpackContentItem> finalFilesToUpdate, long startFetching, @Nullable FetchManager fetchManager)
			throws InterruptedException {
		return downloadModpack(finalFilesToUpdate, startFetching, fetchManager, true);
	}

	private boolean downloadModpack(Set<Jsons.ModpackContentFields.ModpackContentItem> finalFilesToUpdate, long startFetching, @Nullable FetchManager fetchManager,
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

			Path downloadFile = SmartFileUtils.getPath(storage.activeDirectory(), serverFilePath);

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

	private void reportFailedDownloads(long start) {
		if (failedDownloads.isEmpty()) {
			LOGGER.error("Update download did not complete. Try again! Took: {}ms", System.currentTimeMillis() - start);
			return;
		}

		StringBuilder failedFiles = new StringBuilder();
		for (var download : failedDownloads.entrySet()) {
			var item = download.getKey();
			var urls = download.getValue();
			LOGGER.error("Failed to download: {} from {}", item.file, urls);
			failedFiles.append(item.file);
		}

		new ScreenManager().error("automodpack.error.files", "Failed to download: " + failedFiles, "automodpack.error.logs");
		LOGGER.error("Update failed successfully! Try again! Took: {}ms", System.currentTimeMillis() - start);
	}

	// this is run every time we modpack is updated
	private ApplyResult applyPreparedPlan(PreparedPlan prepared, SelectedModpackTarget target) throws Exception {
		executePlan(prepared, target);
		UpdatePlan plan = prepared.plan();

		EnumSet<RestartReason> restartReasons = plan.restartReasons().stream().map(reason -> RestartReason.valueOf(reason.name()))
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(RestartReason.class)));
		ApplyResult result = new ApplyResult(restartReasons);
		changelogs.setRestartReasons(result.reasonDescriptions());
		if (result.requiresRestart()) LOGGER.info("Restart required because: {}", String.join(", ", result.reasonDescriptions()));
		return result;
	}

	private void recordChangelogs(PreparedPlan prepared, SelectedModpackTarget target) {
		UpdatePreview applied = UpdatePreview.create(prepared.plan(), prepared.originalFiles(), target.flatTarget(), target.selection(), false, null,
				target.generationRecord().metadata().patchNotes(), target.patchNotesHistory());
		Map<UpdatePlan.FileKey, List<String>> mainPageUrls = resolveMainPageUrls(prepared);
		changelogs.clear();
		changelogs.setPatchNotes(applied.latestPatchNotes(), applied.patchNotesHistory());
		for (UpdatePreview.Entry entry : applied.entries()) {
			UpdatePlan.FileKey file = new UpdatePlan.FileKey(entry.root(), entry.relativePath());
			switch (entry.kind()) {
				case ADDED, CHANGED, RESTORED_BASELINE -> changelogs.recordChanged(file, mainPageUrls.getOrDefault(file, List.of()));
				case REMOVED -> changelogs.recordRemoved(file, mainPageUrls.getOrDefault(file, List.of()));
				default -> {
				}
			}
		}
		LOGGER.info("Prepared update changes: {} changed, {} removed", changelogs.changedFiles().size(), changelogs.removedFiles().size());
	}

	private Map<UpdatePlan.FileKey, List<String>> resolveMainPageUrls(PreparedPlan prepared) {
		FetchManager manager = sourceFetchManager;
		if (manager == null) return Map.of();
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
		return Map.copyOf(resolved);
	}

	private PreparedPlan buildPlan(FileMetadataCache cache, ModFileCache modCache, Jsons.ModpackContentFields target) throws Exception {
		return buildPlan(cache, modCache, target, true);
	}

	private PreparedPlan buildPlan(FileMetadataCache cache, ModFileCache modCache, Jsons.ModpackContentFields target, boolean prepareObjects) throws Exception {
		captureActiveEditableOverlays(cache);
		Jsons.ModpackContentFields installed = storedTarget();
		Map<String, ClientOverlaySnapshot> overlaySnapshots = new HashMap<>();
		ClientOverlaySnapshot targetOverlay = storage.overlaySnapshot(target.modpackId, cache);
		overlaySnapshots.put(target.modpackId, targetOverlay);
		UpdatePlanner.SelectionContext selection = selectionContext(cache, overlaySnapshots);
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(target, installed, selection, cache, overlaySnapshots);
		if (prepareObjects) populateStoreFromActive(target, cache);
		Set<String> forceCopyServices = getForceCopyMods(target).stream().map(UpdatePlanner::normalize).collect(Collectors.toSet());
		List<UpdatePlan.ModInfo> targetMods = inspectTargetMods(target, cache, modCache);
		List<UpdatePlan.ModInfo> standardMods = inspectStandardMods(cache, modCache);
		List<UpdatePlan.NestedCopy> nestedCopies = prepareObjects ? inspectNestedCopies(target, cache) : List.of();
		Jsons.ClientConfigFieldsV3 plannedConfig = connectionInfo == null || !connectionInfo.isComplete()
				? ModpackUtils.planCachedModpackSelection(target.modpackId)
				: ModpackUtils.planModpackSelection(target.modpackId, connectionInfo);
		Map<String, UpdatePlan.FileState> editableOverlays = files.entrySet().stream().filter(entry -> entry.getKey().root() == UpdatePlan.Root.OVERLAY)
				.collect(Collectors.toMap(entry -> entry.getKey().relativePath(), Map.Entry::getValue));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target, files, editableOverlays, forceCopyServices, targetMods, standardMods, nestedCopies, selection, plannedConfig));
		if (!LauncherVersionSwapper.requiresLoaderVersionSwap(target.loader, target.loaderVersion)) return new PreparedPlan(plan, files, targetOverlay.digest());
		Set<UpdatePlan.RestartReason> restartReasons = EnumSet.noneOf(UpdatePlan.RestartReason.class);
		restartReasons.addAll(plan.restartReasons());
		restartReasons.add(UpdatePlan.RestartReason.CHANGED_LOADER_VERSION);
		UpdatePlan withLoaderRestart = new UpdatePlan(plan.modpackId(), plan.generationTarget(), plan.operations(), plan.projectedFinalState(), plan.plannedClientConfig(),
				restartReasons, plan.preservations(), plan.baselineCaptures(), plan.conflicts());
		return new PreparedPlan(withLoaderRestart, files, targetOverlay.digest());
	}

	private void captureActiveEditableOverlays(FileMetadataCache cache) throws IOException {
		SelectedModpackTarget activeTarget = storedSelectedTarget();
		if (activeTarget == null || activeTarget.flatTarget().list == null) return;
		storage.ensureRoots();
		Set<String> deletedPaths = new TreeSet<>(storage.readOverlayState(activeTarget.manifest().modpackId()).deletedPaths);
		for (var item : activeTarget.flatTarget().list) {
			if (!item.editable) continue;
			Path live = livePath(item);
			Path overlay = storage.overlayFile(activeTarget.manifest().modpackId(), item.file);
			if (!Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS)) {
				Files.deleteIfExists(overlay);
				deletedPaths.add(UpdatePlanner.normalize(item.file));
				continue;
			}
			String hash = cache.getHashOrNull(live);
			if (hash == null) {
				hash = HashUtils.getHash(live);
				if (hash != null) cache.overwriteCache(live, hash);
			}
			if (hash == null) throw new IOException("Failed to hash editable live file: " + live);
			long size = Files.size(live);
			if (item.sha1.equalsIgnoreCase(hash) && Long.parseLong(item.size) == size) {
				Files.deleteIfExists(overlay);
				deletedPaths.remove(UpdatePlanner.normalize(item.file));
				continue;
			}
			Path object = storage.objectsDirectory().resolve(hash);
			if (!SmartFileUtils.isValidFile(object, size, hash)) SmartFileUtils.copyVerifiedAtomic(live, object, size, hash);
			SmartFileUtils.copyVerifiedAtomic(object, overlay, size, hash);
			deletedPaths.remove(UpdatePlanner.normalize(item.file));
		}
		storage.writeOverlayState(activeTarget.manifest().modpackId(), deletedPaths);
	}

	private Path livePath(Jsons.ModpackContentFields.ModpackContentItem item) {
		String relative = UpdatePlanner.normalize(item.file);
		return storage.gameDirectory().resolve(relative);
	}

	private PreviewRequestResult requestUpdatePreview(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate) throws Exception {
		if (selectedTarget == null) throw new IllegalStateException("Selected modpack target is unavailable");
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			PreparedPlan prepared = buildPlan(cache, modCache, selectedTarget.flatTarget(), false);
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
				startUpdateAfterPreview(filesToUpdate);
			};
			Runnable cancelAction = firstConnection
					? () -> confirmationState.compareAndSet(ConfirmationState.PREVIEWING, ConfirmationState.WAITING)
					: this::close;
			return requestPreparedPlanPreview(prepared, continueAction, cancelAction, firstConnection)
					? PreviewRequestResult.PREVIEW_SHOWN
					: PreviewRequestResult.PREVIEW_NOT_SHOWN;
		}
	}

	/** A review is required for the first install or any plan that changes player-visible state. */
	private boolean requiresPlayerReview(PreparedPlan prepared, boolean firstInstall) {
		if (firstInstall) return true;
		return hasPlanImpact(prepared);
	}

	/** Login reconciliation must also advance a newly advertised generation, even when its files are unchanged. */
	private boolean requiresReconciliation(PreparedPlan prepared, Jsons.ModpackContentFields installed) {
		return installed == null || !GenerationTarget.fromFlat(installed).equals(prepared.plan().generationTarget()) || hasPlanImpact(prepared);
	}

	private boolean hasPlanImpact(PreparedPlan prepared) {
		UpdatePlan plan = prepared.plan();
		return !plan.operations().isEmpty() || !plan.conflicts().isEmpty() || !plan.preservations().isEmpty() || !plan.baselineCaptures().isEmpty()
				|| !plan.restartReasons().isEmpty() || !ConfigTools.GSON.toJson(plan.plannedClientConfig()).equals(ConfigTools.GSON.toJson(clientConfig));
	}

	private boolean requestPreparedPlanPreview(PreparedPlan prepared, Runnable continueAction, Runnable cancelAction, boolean returnToSelection) throws IOException {
		List<GenerationPatchNoteHistory.Entry> missedPatchNotes = GenerationPatchNoteHistory.after(selectedTarget.patchNotesHistory(), installedGenerationId());
		UpdatePreview preview = UpdatePreview.create(prepared.plan(), prepared.originalFiles(), selectedTarget.flatTarget(), selectedTarget.selection(), false, null, getPatchNotes(), missedPatchNotes);
		return new ScreenManager().preview(preview, getModpackName(),
				(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(continueAction), cancelAction, false, returnToSelection, resolveMainPageUrls(prepared));
	}

	private String installedGenerationId() throws IOException {
		Jsons.ClientGenerationStateFields state = storage.readActiveState();
		return state != null && selectedTarget != null && selectedTarget.manifest().modpackId().equals(state.modpackId) ? state.generationId : "";
	}

	private UpdatePlanner.SelectionContext selectionContext(FileMetadataCache cache, Map<String, ClientOverlaySnapshot> overlaySnapshots) throws IOException {
		String previousId = clientConfig.selectedModpackId;
		if (previousId == null || previousId.isBlank() || !ModpackId.isValid(previousId)) return null;
		Jsons.ModpackContentFields previousManifest = storedTarget();
		ClientOverlaySnapshot snapshot = overlaySnapshots.get(previousId);
		if (snapshot == null) {
			snapshot = storage.overlaySnapshot(previousId, cache);
			overlaySnapshots.put(previousId, snapshot);
		}
		return new UpdatePlanner.SelectionContext(previousId, previousManifest, snapshot.files());
	}

	private void populateStoreFromActive(Jsons.ModpackContentFields target, FileMetadataCache cache) throws IOException {
		if (target.list == null) return;
		for (var item : target.list) {
			Path object = storage.objectsDirectory().resolve(item.sha1);
			long size = Long.parseLong(item.size);
			if (SmartFileUtils.isValidFile(object, size, item.sha1)) continue;
			Path source = SmartFileUtils.getPath(storage.activeDirectory(), item.file);
			populateStoreObject(source, object, size, item.sha1, cache);
		}
	}

	private void populateStoreFromCachedLocations(Jsons.ModpackContentFields target, FileMetadataCache cache) throws IOException {
		if (target.list == null) return;
		for (var item : target.list) {
			long size = Long.parseLong(item.size);
			Path object = storage.objectsDirectory().resolve(item.sha1);
			if (SmartFileUtils.isValidFile(object, size, item.sha1)) continue;
			Path source = SmartFileUtils.getPath(storage.activeDirectory(), item.file);
			if (!SmartFileUtils.isValidFile(source, size, item.sha1)) source = livePath(item);
			populateStoreObject(source, object, size, item.sha1, cache);
		}
	}

	private static void populateStoreObject(Path source, Path object, long size, String sha1, FileMetadataCache cache) throws IOException {
		if (!SmartFileUtils.isValidFile(source, size, sha1)) return;
		SmartFileUtils.copyVerifiedAtomic(source, object, size, sha1);
		cache.overwriteCache(object, sha1);
	}

	private Map<UpdatePlan.FileKey, UpdatePlan.FileState> inspectFiles(Jsons.ModpackContentFields target, Jsons.ModpackContentFields installed,
			UpdatePlanner.SelectionContext selection, FileMetadataCache cache, Map<String, ClientOverlaySnapshot> overlaySnapshots) throws IOException {
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = new HashMap<>();
		if (Files.isDirectory(storage.activeDirectory())) {
			try (Stream<Path> stream = Files.walk(storage.activeDirectory())) {
				for (Path path : stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
					putFileState(files, UpdatePlan.Root.PROJECTION, storage.activeDirectory(), path, cache);
			}
		}
		ClientOverlaySnapshot targetOverlay = overlaySnapshots.get(target.modpackId);
		if (targetOverlay == null) {
			targetOverlay = storage.overlaySnapshot(target.modpackId, cache);
			overlaySnapshots.put(target.modpackId, targetOverlay);
		}
		for (var entry : targetOverlay.files().entrySet())
			files.put(new UpdatePlan.FileKey(UpdatePlan.Root.OVERLAY, entry.getKey()), entry.getValue());
		Set<String> gamePaths = new HashSet<>();
		if (target.list != null) target.list.forEach(item -> gamePaths.add(item.file));
		if (installed != null && installed.list != null) installed.list.forEach(item -> gamePaths.add(item.file));
		if (selection != null && selection.previousManifest() != null && selection.previousManifest().list != null) selection.previousManifest().list.forEach(item -> gamePaths.add(item.file));
		if (installed != null && installed.ownershipLedger != null && installed.ownershipLedger.entries != null)
			installed.ownershipLedger.entries.forEach(entry -> {
				if (entry != null) gamePaths.add(entry.logicalPath);
			});
		for (String gamePath : gamePaths) {
			Path path = SmartFileUtils.getPath(storage.gameDirectory(), gamePath);
			if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) putFileState(files, UpdatePlan.Root.GAME_DIR, storage.gameDirectory(), path, cache);
		}
		if (Files.isDirectory(storage.modsDirectory(), LinkOption.NOFOLLOW_LINKS)) {
			try (Stream<Path> stream = Files.list(storage.modsDirectory())) {
				for (Path path : stream.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).toList())
					putFileState(files, UpdatePlan.Root.GAME_DIR, storage.gameDirectory(), path, cache);
			}
		}
		OwnershipLedger ledger = OwnershipLedger.fromFields(target.ownershipLedger);
		for (String logicalPath : ledger.entries().keySet()) {
			Optional<UpdatePlan.FileKey> cleanupKey = UpdatePlanner.managedCleanupKey(logicalPath);
			if (cleanupKey.isEmpty()) continue;
			UpdatePlan.FileKey key = cleanupKey.get();
			if (key.root() != UpdatePlan.Root.GAME_DIR) continue;
			Path path = storage.gameDirectory().resolve(key.relativePath()).normalize();
			if (path.startsWith(storage.gameDirectory()) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) putFileState(files, key.root(), storage.gameDirectory(), path, cache);
		}
		return files;
	}

	private void putFileState(Map<UpdatePlan.FileKey, UpdatePlan.FileState> files, UpdatePlan.Root root, Path rootPath, Path path,
			FileMetadataCache cache) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;
		String relative = UpdatePlanner.normalize(rootPath.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString());
		String hash = cache.getHashOrNull(path);
		if (hash == null) {
			hash = HashUtils.getHash(path);
			if (hash == null) throw new IOException("Failed to hash live file: " + path);
			cache.overwriteCache(path, hash);
		}
		files.put(new UpdatePlan.FileKey(root, relative), new UpdatePlan.FileState(hash, Files.size(path), true, FileInspection.isMod(path)));
	}

	private List<UpdatePlan.ModInfo> inspectTargetMods(Jsons.ModpackContentFields target, FileMetadataCache cache, ModFileCache modCache) {
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		for (var item : target.list.stream().filter(value -> "mod".equals(value.type)).sorted(Comparator.comparing(value -> value.file)).toList()) {
			long size = Long.parseLong(item.size);
			Path source = storage.objectsDirectory().resolve(item.sha1);
			if (!SmartFileUtils.isValidFile(source, size, item.sha1)) source = SmartFileUtils.getPath(storage.activeDirectory(), item.file);
			if (!SmartFileUtils.isValidFile(source, size, item.sha1)) continue;
			FileInspection.Mod mod = modCache.getModOrNull(source, cache);
			if (mod != null) mods.add(new UpdatePlan.ModInfo(UpdatePlanner.normalize(item.file), item.sha1, size, mod.IDs(), mod.deps()));
		}
		return mods;
	}

	private List<UpdatePlan.ModInfo> inspectStandardMods(FileMetadataCache cache, ModFileCache modCache) throws IOException {
		if (!Files.isDirectory(storage.modsDirectory())) return List.of();
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		try (Stream<Path> stream = Files.list(storage.modsDirectory())) {
			for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
				FileInspection.Mod mod = modCache.getModOrNull(path, cache);
				if (mod != null) {
					String relativePath = UpdatePlanner.normalize(storage.gameDirectory().relativize(path.toAbsolutePath().normalize()).toString());
					mods.add(new UpdatePlan.ModInfo(relativePath, mod.hash(), Files.size(path), mod.IDs(), mod.deps()));
				}
			}
		}
		return mods;
	}

	private List<UpdatePlan.NestedCopy> inspectNestedCopies(Jsons.ModpackContentFields target, FileMetadataCache cache) throws IOException {
		Files.createDirectories(storage.incomingDirectory());
		Path inspectionDirectory = Files.createTempDirectory(storage.incomingDirectory(), "inspection-");
		try {
			Path inspectionMods = inspectionDirectory.resolve("mods");
			Files.createDirectories(inspectionMods);
			for (var item : target.list.stream().filter(value -> "mod".equals(value.type)).toList()) {
				Path source = storage.objectsDirectory().resolve(item.sha1);
				if (!SmartFileUtils.isValidFile(source, Long.parseLong(item.size), item.sha1)) source = SmartFileUtils.getPath(storage.activeDirectory(), item.file);
				if (!SmartFileUtils.isValidFile(source, Long.parseLong(item.size), item.sha1)) continue;
				SmartFileUtils.copyVerifiedAtomic(source, inspectionMods.resolve(Path.of(UpdatePlanner.normalize(item.file)).getFileName()), Long.parseLong(item.size),
						item.sha1);
			}

			List<UpdatePlan.NestedCopy> copies = new ArrayList<>();
			for (FileInspection.Mod mod : MODPACK_LOADER.getModpackNestedConflicts(inspectionDirectory, cache)) {
				if (mod.path() == null || mod.hash() == null || !Files.isRegularFile(mod.path())) continue;
				long size = Files.size(mod.path());
				Path storeFile = storage.objectsDirectory().resolve(mod.hash());
				if (!SmartFileUtils.isValidFile(storeFile, size, mod.hash())) SmartFileUtils.copyVerifiedAtomic(mod.path(), storeFile, size, mod.hash());
				Path targetPath = storage.modsDirectory().resolve(mod.path().getFileName()).normalize();
				if (!targetPath.startsWith(storage.gameDirectory())) throw new IOException("Nested mod target escaped the game directory: " + targetPath);
				String relativePath = UpdatePlanner.normalize(storage.gameDirectory().relativize(targetPath).toString());
				copies.add(new UpdatePlan.NestedCopy(relativePath, mod.hash(), size, mod.IDs()));
			}
			return copies;
		} finally {
			try (Stream<Path> stream = Files.walk(inspectionDirectory)) {
				for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}

	private void executePlan(PreparedPlan prepared, SelectedModpackTarget target) throws IOException {
		UpdatePlan plan = prepared.plan();
		ensurePlanObjects(plan, target.flatTarget());
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

	private void populateStoreFromModpack(Collection<Jsons.ModpackContentFields.ModpackContentItem> items, FileMetadataCache cache) {
		for (var item : items) {
			Path storeFile = storage.objectsDirectory().resolve(item.sha1);
			long size = Long.parseLong(item.size);
			if (SmartFileUtils.isValidFile(storeFile, size, item.sha1)) continue;
			Path source = SmartFileUtils.getPath(storage.activeDirectory(), item.file);
			try {
				populateStoreObject(source, storeFile, size, item.sha1, cache);
			} catch (IOException e) {
				LOGGER.warn("Failed to reuse installed modpack object {}", item.file, e);
			}
		}
	}

	private void ensurePlanObjects(UpdatePlan plan, Jsons.ModpackContentFields targetManifest) throws IOException {
		Map<String, Jsons.ModpackContentFields.ModpackContentItem> itemsByHash = targetManifest.list.stream()
				.collect(Collectors.toMap(item -> item.sha1.toLowerCase(Locale.ROOT), item -> item, (first, second) -> first));
		for (UpdatePlan.Operation operation : plan.operations()) {
			if (operation.operation() != UpdatePlan.OperationType.INSTALL_OBJECT) continue;
			Path storeFile = storage.objectsDirectory().resolve(operation.expectedObjectHash());
			if (SmartFileUtils.isValidFile(storeFile, operation.expectedSize(), operation.expectedObjectHash())) continue;
			if (operation.root() == UpdatePlan.Root.OVERLAY) {
				Path overlay = storage.overlayFile(targetManifest.modpackId, operation.relativePath());
				if (SmartFileUtils.isValidFile(overlay, operation.expectedSize(), operation.expectedObjectHash())) {
					SmartFileUtils.copyVerifiedAtomic(overlay, storeFile, operation.expectedSize(), operation.expectedObjectHash());
					continue;
				}
				throw new IOException("Required editable overlay object is unavailable: " + operation.expectedObjectHash());
			}
			var item = itemsByHash.get(operation.expectedObjectHash().toLowerCase(Locale.ROOT));
			if (item == null) throw new IOException("Planned CAS object is unavailable: " + operation.expectedObjectHash());
			Path source = SmartFileUtils.getPath(storage.activeDirectory(), item.file);
			if (!SmartFileUtils.isValidFile(source, operation.expectedSize(), operation.expectedObjectHash())) source = livePath(item);
			if (!SmartFileUtils.isValidFile(source, operation.expectedSize(), operation.expectedObjectHash()))
				throw new IOException("Required object is absent from CAS and verified live locations: " + operation.expectedObjectHash());
			SmartFileUtils.copyVerifiedAtomic(source, storeFile, operation.expectedSize(), operation.expectedObjectHash());
		}
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

	private enum RestartReason {
		REMOVED_NON_MODPACK_FILES("files removed from the modpack were deleted from the game directory"),
		CORRECTED_FILE_LOCATIONS("standard-directory mods were copied or updated"),
		FIXED_NESTED_MODS("conflicting nested mods were copied to the standard mods directory"),
		REMOVED_DUPLICATE_MODS("duplicate standard-directory mods were removed"),
		REMOVED_STANDARD_MODS("modpack-owned mods were removed from the standard mods directory"),
		APPLIED_SERVER_DELETIONS("server-requested mod deletions were applied"),
		CHANGED_LOADER_VERSION("launcher loader-version metadata changed"),
		CHANGED_GROUP_SELECTION("the selected modpack groups changed"),
		SELECTED_MODPACK("the selected stable modpack changed");

		private final String description;

		RestartReason(String description) {
			this.description = description;
		}
	}

	private record PreparedPlan(UpdatePlan plan, Map<UpdatePlan.FileKey, UpdatePlan.FileState> originalFiles, String overlayDigest) {
		private PreparedPlan {
			originalFiles = Map.copyOf(originalFiles);
			if (overlayDigest == null || !overlayDigest.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Prepared overlay digest is invalid");
		}
	}

	private record RemovalPreparation(UpdatePlan plan, Jsons.CompleteModpackContentFields completeFields, Jsons.ModpackContentFields installed,
			Jsons.ClientBaselineFields baseline, SelectionIntent expectedPriorIntent, Jsons.ClientConfigFieldsV3 plannedConfig,
			Map<UpdatePlan.FileKey, UpdatePlan.FileState> files) {
		private RemovalPreparation {
			files = Map.copyOf(files);
		}
	}

	private record ApplyResult(EnumSet<RestartReason> restartReasons) {
		private boolean requiresRestart() {
			return !restartReasons.isEmpty();
		}

		private List<String> reasonIds() {
			return restartReasons.stream().map(Enum::name).toList();
		}

		private List<String> reasonDescriptions() {
			return restartReasons.stream().map(reason -> reason.description).toList();
		}
	}

	private enum ApplyStatus {
		APPLIED, DEFERRED, FAILED
	}

	private enum PreviewRequestResult {
		PREVIEW_SHOWN, PREVIEW_NOT_SHOWN, APPLIED, DEFERRED, FAILED
	}

	// Returns the modpack mods that ship a service file this loader's running version cannot host
	// in place (see ModpackLoaderService#forceCopyServices) - these must be copied into standard
	// mods/ instead of staying in the active projection.
	private Set<String> getForceCopyMods(Jsons.ModpackContentFields modpackContentFields) throws IOException {
		Set<String> forceCopyServices = MODPACK_LOADER.forceCopyServices();
		Set<String> forceCopyMods = new HashSet<>();
		if (forceCopyServices.isEmpty()) return forceCopyMods;

		for (Jsons.ModpackContentFields.ModpackContentItem item : modpackContentFields.list) {
			if (!item.type.equals("mod")) continue;

			long size = Long.parseLong(item.size);
			Path modPath = storage.objectsDirectory().resolve(item.sha1);
			if (!SmartFileUtils.isValidFile(modPath, size, item.sha1)) modPath = SmartFileUtils.getPath(storage.activeDirectory(), item.file);
			if (!SmartFileUtils.isValidFile(modPath, size, item.sha1)) continue;
			try (FileSystem fs = FileSystems.newFileSystem(modPath)) {
				if (!FileInspection.getServices(fs, forceCopyServices).isEmpty()) forceCopyMods.add(item.file);
			}
		}

		return forceCopyMods;
	}
}
