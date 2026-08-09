package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
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
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.LocalModArchive;
import pl.skidam.automodpack_core.update.RecoveryArchive;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePlanner;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.update.UpdateReviewPolicy;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
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
	private ClientUpdatePlanBuilder.PreparedPlan cachedSwitchPlan;
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

	/** The explicit local-mod review is available only before the first generation exists. */
	public boolean isFreshInstall() {
		try {
			return storage.readActiveState() == null || !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS);
		} catch (IOException e) {
			throw new IllegalStateException("Could not determine whether this is a fresh modpack install", e);
		}
	}

	public LocalModArchive.Snapshot localModCandidates() throws IOException {
		if (!isFreshInstall()) return new LocalModArchive.Snapshot(List.of());
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			return LocalModArchive.candidates(storage, THIS_MOD_JAR, cache);
		}
	}

	public void archiveLocalMods(List<LocalModArchive.ArchiveEntry> selected) throws IOException {
		if (!isFreshInstall()) throw new IOException("Local mod cleanup is available only during a fresh modpack install");
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			LocalModArchive.archive(storage, selected, cache);
		}
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
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		if (active != null && selectedTarget.manifest().modpackId().equals(active.modpackId)) throw new IllegalArgumentException("Cached switch target is already active");
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			planBuilder.populateStoreFromCachedLocations(selectedTarget.flatTarget(), cache);
			ClientUpdatePlanBuilder.PreparedPlan prepared = planBuilder.buildPlan(
					new ClientUpdatePlanBuilder.Input(selectedTarget, selectedTarget.flatTarget(), connectionInfo, clientConfig, true), cache, modCache);
			planBuilder.ensurePlanObjects(prepared.plan(), selectedTarget.flatTarget());
			cachedSwitchPlan = prepared;
			List<GenerationPatchNoteHistory.Entry> missedPatchNotes = GenerationPatchNoteHistory.after(selectedTarget.patchNotesHistory(), "");
			return UpdatePreview.create(prepared.plan(), prepared.originalFiles(), selectedTarget.flatTarget(), selectedTarget.selection(), false, null,
					selectedTarget.generationRecord().metadata().patchNotes(), missedPatchNotes);
		}
	}

	/** Applies the last cached switch plan through the normal atomic transaction executor. */
	public void applyCachedSwitch() throws Exception {
		ClientUpdatePlanBuilder.PreparedPlan prepared = cachedSwitchPlan;
		if (prepared == null || selectedTarget == null) throw new IllegalStateException("Cached switch was not prepared");
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

	public Set<ModpackJsons.ModpackContentFields.ModpackContentItem> getModpackFileList() {
		return serverModpackContent.list;
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
		ModpackJsons.ModpackContentFields installed = storedTarget();
		if (installed != null && installed.list != null)
			for (var item : installed.list)
				addSourceFetchData(unique, item.file, item.sha1, item.murmur, item.size, item.type);
		sourceFetchManager = newSourceFetchManager(new ArrayList<>(unique.values()));
	}

	private FetchManager ensureSourceFetch(Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items) {
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
				startSourceFetch();
				if (!beginConfirmation()) throw new IllegalStateException("Modpack confirmation is already active");
				if (!clientConfig.reviewUpdates) startConfirmedUpdate();
				else new ScreenManager().welcome(this);
			} else {
				// Handle existing modpack
				if (result == null) result = ModpackUtils.isUpdate(serverModpackContent, storage);

				startUpdate(result.filesToUpdate());
			}
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(UpdateType.UPDATE, changelogs).restart(preload);
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
		ModpackJsons.ModpackContentFields preloadTarget = selectedTarget.completeTarget();
		Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> targetItems = preloadTarget.list == null ? List.of() : preloadTarget.list;

		long start = System.currentTimeMillis();
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			Set<ModpackJsons.ModpackContentFields.ModpackContentItem> allTargetItems = new LinkedHashSet<>(targetItems);
			ModpackUtils.populateStoreFromCWD(allTargetItems, cache, storage);
			planBuilder.populateStoreFromActive(preloadTarget, cache);
			Set<ModpackJsons.ModpackContentFields.ModpackContentItem> targetSet = uniqueObjects(allTargetItems);
			Set<ModpackJsons.ModpackContentFields.ModpackContentItem> uncached = ModpackUtils.identifyUncachedFiles(targetSet, cache, storage);
			if (uncached.isEmpty()) {
				LOGGER.info("Preload reused all {} verified complete modpack objects", targetSet.size());
			} else {
				totalBytesToDownload = uncached.stream().mapToLong(item -> Long.parseLong(item.size)).sum();
				FetchManager fetchManager = ensureSourceFetch(uncached);
				if (!downloadModpack(uncached, start, fetchManager, false)) throw new IOException("One or more selected modpack objects could not be acquired");
				Set<ModpackJsons.ModpackContentFields.ModpackContentItem> stillUncached = ModpackUtils.identifyUncachedFiles(targetSet, cache, storage);
				if (!stillUncached.isEmpty()) throw new IOException("Verified CAS objects are still missing after preload: " + stillUncached.size());
				LOGGER.info("Preloaded {} complete modpack objects in {}ms", targetSet.size(), System.currentTimeMillis() - start);
			}
		}
		if (clientConfig.reviewUpdates) {
			LOGGER.info("Preload acquired the complete selected target but kept the active projection unchanged because reviewUpdates=true");
			return;
		}
		applyPreloadedTarget(start);
	}

	private void applyPreloadedTarget(long start) throws Exception {
		ClientUpdatePlanBuilder.PreparedPlan prepared;
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			prepared = planBuilder.buildPlan(new ClientUpdatePlanBuilder.Input(selectedTarget, selectedTarget.flatTarget(), connectionInfo, clientConfig, true), cache, modCache);
		}
		LOGGER.info("Preload reviewUpdates=false; applying the prepared selected-target update before the game starts");
		recordChangelogs(prepared, selectedTarget);
		ApplyResult applyResult = applyPreparedPlan(prepared, selectedTarget);
		changelogs.setRestartReasons(applyResult.reasonDescriptions());
		LOGGER.info("Preload applied the selected target transaction successfully; required restart: {} took {}ms", applyResult.requiresRestart(), System.currentTimeMillis() - start);
		restartAfterApply(applyResult);
	}

	private static Set<ModpackJsons.ModpackContentFields.ModpackContentItem> uniqueObjects(Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items) {
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> unique = new LinkedHashMap<>();
		for (var item : items) unique.putIfAbsent(item.sha1.toLowerCase(Locale.ROOT), item);
		return new LinkedHashSet<>(unique.values());
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
		ClientUpdatePlanBuilder.RemovalPreparation preparation = planBuilder.prepareRemoval();
		clientConfig = preparation.currentConfig();
		return UpdatePreview.create(preparation.plan(), preparation.files(), preparation.installed(), removalSelection(preparation), true, preparation.baseline(),
				preparation.completeFields().generation == null ? "" : preparation.completeFields().generation.patchNotes);
	}

	// Remove the installed modpack and restore baseline files before metadata cleanup.
	public UpdateTransactionExecutor.Execution removeModpack() throws Exception {
		ClientUpdatePlanBuilder.RemovalPreparation preparation = planBuilder.prepareRemoval();
		clientConfig = preparation.currentConfig();
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

	private static ResolvedSelection removalSelection(ClientUpdatePlanBuilder.RemovalPreparation preparation) {
		SelectionIntent intent = preparation.expectedPriorIntent();
		if (intent == null) return null;
		Set<String> selected = preparation.installed().selectedGroups == null ? Set.of() : preparation.installed().selectedGroups;
		Set<String> stale = new TreeSet<>(intent.requestedGroups());
		stale.removeAll(selected);
		return new ResolvedSelection(intent, new TreeSet<>(selected), new TreeSet<>(stale));
	}

	// Load the already-installed modpack without contacting the server or
	// reconciling local files against it. Used when update-on-launch is disabled
	// so the user can freely add/remove mods (e.g. a binary search) without
	// AutoModpack restoring or deleting them.
	public Path recoverDeletedFile(String logicalPath, String sha1, long size) throws IOException {
		ModpackJsons.ModpackContentFields installed = storedTarget();
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
		ModpackJsons.ModpackContentFields installed = storedTarget();
		ClientStorageJsons.ClientRecoveryArchiveFields archive = RecoveryArchive.read(recoveryDirectory(installed.modpackId));
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

	public void startUpdate(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate) {
		try {
			requireLiveConnection();
			new ScreenManager().waiting();
			switch (requestUpdatePreview(filesToUpdate)) {
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
			new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
			LOGGER.error("Failed to prepare the modpack update preview", e);
			close();
			return;
		}
	}

	private void startUpdateAfterPreview(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate) {
		long start = System.currentTimeMillis();
		ClientUpdatePlanBuilder.PreparedPlan finalPlan;
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			requireLiveConnection();
			// Don't download files which already exist
			ModpackUtils.populateStoreFromCWD(filesToUpdate, cache, storage);
			var finalFilesToUpdate = ModpackUtils.identifyUncachedFiles(filesToUpdate, cache, storage);

			long startFetching = System.currentTimeMillis();
			for (ModpackJsons.ModpackContentFields.ModpackContentItem serverItem : finalFilesToUpdate) totalBytesToDownload += Long.parseLong(serverItem.size);
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

			finalPlan = planBuilder.buildPlan(new ClientUpdatePlanBuilder.Input(selectedTarget, selectedTarget.flatTarget(), connectionInfo, clientConfig, true), cache, modCache);
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
	private ApplyResult applyPreparedPlan(ClientUpdatePlanBuilder.PreparedPlan prepared, SelectedModpackTarget target) throws Exception {
		executePlan(prepared, target);
		UpdatePlan plan = prepared.plan();

		EnumSet<RestartReason> restartReasons = plan.restartReasons().stream().map(reason -> RestartReason.valueOf(reason.name()))
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(RestartReason.class)));
		ApplyResult result = new ApplyResult(restartReasons);
		changelogs.setRestartReasons(result.reasonDescriptions());
		if (result.requiresRestart()) LOGGER.info("Restart required because: {}", String.join(", ", result.reasonDescriptions()));
		return result;
	}

	private void recordChangelogs(ClientUpdatePlanBuilder.PreparedPlan prepared, SelectedModpackTarget target) {
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

	private Map<UpdatePlan.FileKey, List<String>> resolveMainPageUrls(ClientUpdatePlanBuilder.PreparedPlan prepared) {
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

	private PreviewRequestResult requestUpdatePreview(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate) throws Exception {
		if (selectedTarget == null) throw new IllegalStateException("Selected modpack target is unavailable");
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory())) {
			ClientUpdatePlanBuilder.PreparedPlan prepared = planBuilder.buildPlan(
					new ClientUpdatePlanBuilder.Input(selectedTarget, selectedTarget.flatTarget(), connectionInfo, clientConfig, false), cache, modCache);
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

	/** A review is required for first install, a changed generation identity, or any plan impact when enabled by the client. */
	private boolean requiresPlayerReview(ClientUpdatePlanBuilder.PreparedPlan prepared, boolean firstInstall) throws IOException {
		ModpackJsons.ModpackContentFields installed = storedTarget();
		GenerationTarget installedTarget = installed == null ? null : GenerationTarget.fromFlat(installed);
		return UpdateReviewPolicy.requiresPlayerReview(firstInstall, installedTarget, prepared.plan().generationTarget(), hasPlanImpact(prepared), clientConfig.reviewUpdates);
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
		List<GenerationPatchNoteHistory.Entry> missedPatchNotes = GenerationPatchNoteHistory.after(selectedTarget.patchNotesHistory(), installedGenerationId());
		UpdatePreview preview = UpdatePreview.create(prepared.plan(), prepared.originalFiles(), selectedTarget.flatTarget(), selectedTarget.selection(), false, null, getPatchNotes(), missedPatchNotes);
		return new ScreenManager().preview(preview, getModpackName(),
				(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(continueAction), cancelAction, false, returnToSelection, resolveMainPageUrls(prepared));
	}

	private String installedGenerationId() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		return state != null && selectedTarget != null && selectedTarget.manifest().modpackId().equals(state.modpackId) ? state.generationId : "";
	}

	private void executePlan(ClientUpdatePlanBuilder.PreparedPlan prepared, SelectedModpackTarget target) throws IOException {
		UpdatePlan plan = prepared.plan();
		planBuilder.ensurePlanObjects(plan, target.flatTarget());
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

}
