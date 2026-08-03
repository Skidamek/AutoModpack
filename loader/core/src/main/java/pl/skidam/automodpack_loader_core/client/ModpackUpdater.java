package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientContentHistory;
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
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_core.utils.launchers.LauncherVersionSwapper;
import pl.skidam.automodpack_loader_core.DetachedUpdateHelper;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.UpdateTransactionSupport;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

// TODO: clean up this mess
public class ModpackUpdater implements AutoCloseable {
	private static final long CONFIRMATION_TIMEOUT_MINUTES = 5;
	private static final ScheduledExecutorService CONFIRMATION_TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "AutoModpack confirmation timer");
		thread.setDaemon(true);
		return thread;
	});

	public Changelogs changelogs = new Changelogs();
	public DownloadManager downloadManager;
	public long totalBytesToDownload = 0;
	public boolean fullDownload = false;
	private boolean firstConnection;
	private SelectedModpackTarget selectedTarget;
	private Jsons.ModpackContentFields serverModpackContent;
	public Map<Jsons.ModpackContentFields.ModpackContentItem, List<String>> failedDownloads = new HashMap<>();
	private final Map<Jsons.ModpackContentFields.ModpackContentItem, DownloadManager.FailureCategory> failedDownloadCategories = new HashMap<>();
	private final Jsons.ConnectionInfo connectionInfo;
	private final DownloadClient downloadClient;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicReference<ConfirmationState> confirmationState = new AtomicReference<>(ConfirmationState.INACTIVE);
	private final UpdateLoopDetector updateLoopDetector = new UpdateLoopDetector();
	private volatile ScheduledFuture<?> confirmationExpiry;
	private Path modpackDir;
	private Path modpackContentFile;
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

	public String getModpackName() {
		return serverModpackContent.modpackName;
	}

	public SelectedModpackTarget getSelectedTarget() {
		return Objects.requireNonNull(selectedTarget, "Selected modpack target is unavailable");
	}

	public String getPatchNotes() {
		return getSelectedTarget().generationRecord().metadata().patchNotes();
	}

	public Set<Jsons.ModpackContentFields.ModpackContentItem> getModpackFileList() {
		return serverModpackContent.list;
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
		if (!confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.STARTED)) return;
		cancelConfirmationExpiry();
		DownloadClient.NET_EXECUTOR.execute(() -> startUpdate(getModpackFileList()));
	}

	public void cancelConfirmation() {
		if (!confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.CANCELLED)) return;
		cancelConfirmationExpiry();
		close();
	}

	public ModpackUpdater(SelectedModpackTarget selectedTarget, Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret, Path modpackPath) {
		this(selectedTarget, connectionInfo, secret, modpackPath, null);
	}

	public ModpackUpdater(Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret, Path modpackPath) {
		this(null, connectionInfo, secret, modpackPath, null);
	}

	public ModpackUpdater(SelectedModpackTarget selectedTarget, Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret, Path modpackPath,
			DownloadClient downloadClient) {
		this.selectedTarget = selectedTarget;
		this.serverModpackContent = selectedTarget == null ? null : selectedTarget.flatTarget();
		this.connectionInfo = connectionInfo;
		this.modpackDir = modpackPath;
		this.downloadClient = downloadClient;

		if (this.modpackDir == null) throw new IllegalArgumentException("modpackPath is null");
	}

	public void processModpackUpdate(ModpackUtils.UpdateCheckResult result) {
		try {
			modpackContentFile = modpackDir.resolve(modpackContentFileName);
			if (preload) {
				// Preload has no player-facing screen. Keep the installed tree untouched and let the
				// first normal connection present the same preview that all other updates use.
				loadModpack();
				close();
				return;
			}
			requireLiveConnection();

			if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Selected modpack target is unavailable");

			// Handle new modpack
			if (!Files.exists(modpackContentFile)) {
				firstConnection = true;
				fullDownload = true;
				if (!beginConfirmation()) throw new IllegalStateException("Modpack confirmation is already active");
				new ScreenManager().welcome(this);
			} else {
				// Handle existing modpack
				if (result == null) result = ModpackUtils.isUpdate(serverModpackContent, modpackDir);

				// Always show the preview. A zero-file result can still require metadata, selection,
				// ledger, or restart work.
				startUpdate(result.filesToUpdate());
			}
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(modpackDir, UpdateType.UPDATE, changelogs).restart(preload);
			close();
		} catch (Exception e) {
			LOGGER.error("Error while initializing modpack updater", e);
			close();
		}
	}

	public boolean requiresUpdateBeforeLogin(ModpackUtils.UpdateCheckResult result) throws Exception {
		if (result == null || result.requiresUpdate()) return true;
		modpackContentFile = modpackDir.resolve(modpackContentFileName);
		if (!Files.exists(modpackContentFile)) return true;
		if (selectedTarget == null || serverModpackContent == null) throw new IllegalStateException("Selected modpack target is unavailable");

		try (var cache = FileMetadataCache.open(hashCacheDBFile); var modCache = ModFileCache.open(modCacheDBFile)) {
			PreparedPlan prepared = buildPlan(cache, modCache, selectedTarget.flatTarget(), false);
			Jsons.ModpackContentFields installed = ModpackContentTools.read(modpackContentFile);
			if (installed == null || !GenerationTarget.fromFlat(installed).equals(prepared.plan().generationTarget())) return true;
			if (!prepared.plan().operations().isEmpty() || !prepared.plan().restartReasons().isEmpty()) return true;
			return !ConfigTools.GSON.toJson(prepared.plan().plannedClientConfig()).equals(ConfigTools.GSON.toJson(clientConfig));
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
		UpdateTransaction transaction = UpdateTransaction.createRemoval(preparation.plan(), preparation.completeFields(), preparation.installed(), modpackDir,
				ClientPlatform.current(), preparation.expectedPriorIntent());
		UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor(transaction).commit(transaction);
		if (execution.success()) {
			clientConfig = preparation.plannedConfig();
			collectClientObjects();
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
		modpackContentFile = modpackDir.resolve(modpackContentFileName);
		Jsons.ModpackContentFields installed = ModpackContentTools.read(modpackContentFile);
		if (installed == null) throw new IOException("Installed modpack content is missing");
		Path completeCataloguePath = modpackDir.resolve(modpackCatalogueFileName);
		Jsons.CompleteModpackContentFields completeFields = ModpackContentTools.readCompleteFields(completeCataloguePath);
		if (completeFields == null) throw new IOException("Complete modpack catalogue is missing");
		Jsons.ClientBaselineFields baseline = ConfigTools.read(modpackDir.resolve(modpackBaselineFileName), Jsons.ClientBaselineFields.class)
				.orElseGet(() -> {
					Jsons.ClientBaselineFields empty = new Jsons.ClientBaselineFields();
					empty.modpackId = installed.modpackId;
					return empty;
				});
		Jsons.ClientConfigFieldsV3 currentConfig = ConfigTools.read(SmartFileUtils.CWD.resolve(clientConfigFile), Jsons.ClientConfigFieldsV3.class)
				.orElseGet(Jsons.ClientConfigFieldsV3::new);
		if (currentConfig.modpackConnections == null) currentConfig.modpackConnections = new HashMap<>();
		Jsons.ClientConfigFieldsV3 plannedConfig = new Jsons.ClientConfigFieldsV3(currentConfig);
		plannedConfig.modpackConnections.remove(installed.modpackId);
		if (installed.modpackId.equals(plannedConfig.selectedModpackId)) plannedConfig.selectedModpackId = "";
		clientConfig = currentConfig;

		try (var cache = FileMetadataCache.open(hashCacheDBFile)) {
			Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(installed, installed, null, cache);
			Set<String> availableBaselineObjects = new HashSet<>();
			if (baseline.entries != null) for (var entry : baseline.entries) {
				if (entry == null || entry.absent || entry.objectHash == null || entry.size < 0) continue;
				String hash = entry.objectHash.toLowerCase(Locale.ROOT);
				if (SmartFileUtils.isValidFile(clientGenerationObjectsDir.resolve(hash), entry.size, hash)) availableBaselineObjects.add(hash);
			}
			UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, availableBaselineObjects, plannedConfig));
			SelectionIntent expectedPriorIntent = new ClientSelectionStore(SmartFileUtils.CWD.resolve(clientSelectionFile)).get(installed.modpackId).orElse(null);
			return new RemovalPreparation(plan, completeFields, installed, baseline, expectedPriorIntent, plannedConfig, files);
		}
	}

	// Load the already-installed modpack without contacting the server or
	// reconciling local files against it. Used when update-on-launch is disabled
	// so the user can freely add/remove mods (e.g. a binary search) without
	// AutoModpack restoring or deleting them.
	public Path recoverDeletedFile(String logicalPath, String sha1, long size) throws IOException {
		Jsons.ModpackContentFields installed = ModpackContentTools.read(modpackDir.resolve(modpackContentFileName));
		if (installed == null) throw new IOException("Installed modpack content is missing");
		String normalizedPath = UpdatePlanner.normalize(logicalPath);
		if (UpdatePlanner.managedCleanupKey(normalizedPath).isEmpty()) throw new IOException("Recovery path is not managed: " + normalizedPath);
		OwnershipLedger ledger = OwnershipLedger.fromFields(installed.ownershipLedger);
		OwnershipLedger.Entry entry = ledger.entries().get(normalizedPath);
		String normalizedHash = sha1 == null ? null : sha1.toLowerCase(Locale.ROOT);
		if (entry == null || normalizedHash == null || !entry.historicalHashes().contains(new OwnershipLedger.Content(normalizedHash, size)))
			throw new IOException("Recovery object is not owned by the installed modpack ledger");
		Path archiveDirectory = recoveryDirectory(installed.modpackId);
		return RecoveryArchive.archive(SmartFileUtils.CWD.resolve(clientGenerationObjectsDir), archiveDirectory, normalizedPath, normalizedHash, size,
				entry.lastPublishedGenerationId(), Instant.now().toString());
	}

	public RecoverySnapshot recoverySnapshot() throws IOException {
		Jsons.ModpackContentFields installed = ModpackContentTools.read(modpackDir.resolve(modpackContentFileName));
		if (installed == null) throw new IOException("Installed modpack content is missing");
		Jsons.ClientRecoveryArchiveFields archive = RecoveryArchive.read(recoveryDirectory(installed.modpackId));
		List<RecoveryFile> archived = new ArrayList<>();
		for (var entry : archive.entries) archived.add(new RecoveryFile(entry.logicalPath, entry.sha1, entry.size, entry.sourceGenerationId, entry.preservedAt));
		archived.sort(RECOVERY_FILE_ORDER);
		Set<String> archivedKeys = archived.stream().map(ModpackUpdater::recoveryKey).collect(Collectors.toSet());
		Set<String> targetPaths = new HashSet<>();
		if (installed.list != null) for (var item : installed.list) targetPaths.add(UpdatePlanner.normalize(item.file));
		OwnershipLedger ledger = OwnershipLedger.fromFields(installed.ownershipLedger);
		List<RecoveryFile> available = new ArrayList<>();
		Path storeRoot = SmartFileUtils.CWD.resolve(clientGenerationObjectsDir);
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

	private static Path recoveryDirectory(String modpackId) throws IOException {
		try {
			Path recoveryRoot = SmartFileUtils.CWD.resolve(recoveryDir).toAbsolutePath().normalize();
			if (Files.isSymbolicLink(recoveryRoot)) throw new IOException("Recovery archive root may not be a symbolic link");
			Path archiveDirectory = recoveryRoot.resolve(ModpackId.requireValid(modpackId)).normalize();
			if (Files.isSymbolicLink(archiveDirectory)) throw new IOException("Recovery archive modpack directory may not be a symbolic link");
			return archiveDirectory;
		} catch (IllegalArgumentException e) {
			throw new IOException("Recovery modpack ID is invalid", e);
		}
	}

	// Load the already-installed modpack without contacting the server or
	// reconciling local files against it. Used when update-on-launch is disabled
	// so the user can freely add/remove mods (e.g. a binary search) without
	// AutoModpack restoring or deleting them.
	public void loadModpack() throws Exception {

		if (!Files.exists(modpackDir)) return;
		try (var cache = FileMetadataCache.open(hashCacheDBFile)) {
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
		new ReLauncher(modpackDir, updateType, changelogs).restart(false);
	}

	private String updateStateFingerprint(ApplyResult applyResult) {
		String contentHash = HashUtils.getHash(modpackContentFile);
		if (contentHash == null) {
			LOGGER.warn("Cannot track rapid modpack restarts because the content hash is unavailable: {}", modpackContentFile);
			return null;
		}

		return String.join("\n", modpackDir.toAbsolutePath().normalize().toString(), contentHash, String.join(",", applyResult.reasonIds()));
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
		try (Stream<Path> standardModsStream = Files.list(MODS_DIR)) {
			standardModsHashes = standardModsStream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".jar")) // Check extension/type before
					.map(cache::getHashOrNull) // Safe wrapper for IOException
					.filter(Objects::nonNull).collect(Collectors.toSet()); // Use Set for O(1) performance
		} catch (IOException e) {
			LOGGER.error("Failed to list standard mods directory", e);
			standardModsHashes = Collections.emptySet();
		}

		// 2. Filter modpack mods excluding those already present in standard mods
		Path modpackModsDir = modpackDir.resolve("mods");
		if (Files.exists(modpackModsDir)) {
			try (Stream<Path> modpackModsStream = Files.list(modpackModsDir)) {
				final Set<String> finalStandardModsHashes = standardModsHashes;
				modpackMods = modpackModsStream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".jar")).filter(mod -> {
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
			if (preload) {
				LOGGER.info("Deferring modpack update until the client has a player-facing screen");
				close();
				return;
			}
			requireLiveConnection();
			if (requestUpdatePreview(filesToUpdate)) return;
			LOGGER.warn("Update preview was not shown; leaving the installed modpack unchanged");
			close();
		} catch (Exception e) {
			new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
			LOGGER.error("Failed to prepare the modpack update preview", e);
			close();
			return;
		}
	}

	private void startUpdateAfterPreview(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate, PreparedPlan previewPlan) {
		long start = System.currentTimeMillis();

		try (var cache = FileMetadataCache.open(hashCacheDBFile); var modCache = ModFileCache.open(modCacheDBFile)) {
			requireLiveConnection();
			// Don't download files which already exist
			ModpackUtils.populateStoreFromCWD(filesToUpdate, cache);
			var finalFilesToUpdate = ModpackUtils.identifyUncachedFiles(filesToUpdate, cache);

			// FETCH
			long startFetching = System.currentTimeMillis();
			List<FetchManager.FetchData> fetchDatas = new ArrayList<>();

			for (Jsons.ModpackContentFields.ModpackContentItem serverItem : finalFilesToUpdate) {

				totalBytesToDownload += Long.parseLong(serverItem.size);
				String fileType = serverItem.type;

				// Check if the file is mod, shaderpack or resourcepack is available to download from modrinth or curseforge
				if (fileType.equals("mod") || fileType.equals("shader") || fileType.equals("resourcepack")) {
					fetchDatas.add(new FetchManager.FetchData(serverItem.file, serverItem.sha1, serverItem.murmur, serverItem.size, fileType));
				}
			}

			FetchManager fetchManager = null;

			if (!fetchDatas.isEmpty()) {
				fetchManager = new FetchManager(fetchDatas);
				new ScreenManager().fetch(fetchManager);
				fetchManager.fetch();
				LOGGER.info("Finished fetching urls in {}ms", System.currentTimeMillis() - startFetching);
			}

			// DOWNLOAD
			try {
				if (!downloadModpack(finalFilesToUpdate, startFetching, fetchManager, cache)) {
					reportFailedDownloads(start);
					return;
				}
			} catch (Exception e) {
				if (downloadManager != null) downloadManager.cancelAllAndShutdown();
				throw e;
			}

			PreparedPlan finalPlan = buildPlan(cache, modCache, selectedTarget.flatTarget());
			if (!previewPlan.equals(finalPlan)) {
				if (!requestPreparedPlanPreview(finalPlan, () -> executePreparedPlanAfterDownload(finalPlan), () -> {
					close();
					if (firstConnection) new ScreenManager().title();
				})) {
					throw new IOException("The update preview screen is unavailable");
				}
				return;
			}

			ApplyResult applyResult = applyPreparedPlan(finalPlan, selectedTarget);

			boolean requiredRestart = applyResult.requiresRestart();
			LOGGER.info("Update completed! Required restart: {} Took: {}ms", requiredRestart, System.currentTimeMillis() - start);
			restartAfterApply(applyResult);
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(modpackDir, UpdateType.UPDATE, changelogs).restart(preload);
		} catch (SocketTimeoutException | ConnectException e) {
			String host = connectionInfo == null || connectionInfo.endpoint == null ? "modpack host" : "Modpack host of " + connectionInfo.endpoint.getHostString();
			LOGGER.error("{} is not responding", host, e);
		} catch (InterruptedException e) {
			LOGGER.info("Interrupted the download");
		} catch (Exception e) {
			new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
			LOGGER.error("Critical error during modpack update", e);
		} finally {
			close();
		}
	}

	private void executePreparedPlanAfterDownload(PreparedPlan prepared) {
		try {
			ApplyResult applyResult = applyPreparedPlan(prepared, selectedTarget);
			restartAfterApply(applyResult);
		} catch (UpdateDeferredException e) {
			LOGGER.warn("Update transaction {} is waiting for the detached helper to release {}", e.getTransactionId(), e.getBlockedPath());
			new ReLauncher(modpackDir, UpdateType.UPDATE, changelogs).restart(preload);
		} catch (Exception e) {
			new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
			LOGGER.error("Critical error while applying the final modpack update plan", e);
		} finally {
			close();
		}
	}

	private void requireLiveConnection() throws IOException {
		if (connectionInfo == null || !connectionInfo.isComplete()) throw new IOException("Modpack connection is unavailable");
		if (downloadClient == null) throw new IOException("Modpack transfer session is unavailable");
	}

	private boolean downloadModpack(Set<Jsons.ModpackContentFields.ModpackContentItem> finalFilesToUpdate, long startFetching, @Nullable FetchManager fetchManager,
			FileMetadataCache cache) throws InterruptedException {
		int wholeQueue = finalFilesToUpdate.size();

		if (wholeQueue == 0) {
			LOGGER.info("No files to download.");
			return true;
		}

		LOGGER.info("In queue left {} files to download ({}MB)", wholeQueue, totalBytesToDownload / 1024 / 1024);

		if (downloadClient == null) return false;

		downloadManager = new DownloadManager(totalBytesToDownload);
		new ScreenManager().download(downloadManager, getModpackName());
		downloadManager.attachDownloadClient(downloadClient);

		for (var serverItem : finalFilesToUpdate) {

			String serverFilePath = serverItem.file;
			String serverFileHash = serverItem.sha1;
			long serverFileSize = Long.parseLong(serverItem.size);

			Path downloadFile = SmartFileUtils.getPath(modpackDir, serverFilePath);

			List<DownloadSource> sources = new ArrayList<>();
			if (fetchManager != null && fetchManager.getFetchDatas().containsKey(serverFileHash)) {
				sources.addAll(fetchManager.getFetchDatas().get(serverFileHash).fetchedData().sources());
			}

			Consumer<DownloadManager.FailureCategory> failureCallback = category -> {
				failedDownloads.put(serverItem, sources.stream().map(DownloadSource::url).toList());
				failedDownloadCategories.put(serverItem, category);
			};

			Runnable successCallback = () -> {
				List<String> mainPageUrls = new LinkedList<>();
				if (fetchManager != null && fetchManager.getFetchDatas().get(serverFileHash) != null) {
					mainPageUrls = fetchManager.getFetchDatas().get(serverFileHash).fetchedData().mainPageUrls();
				}

				changelogs.changesAddedList.put(downloadFile.getFileName().toString(), mainPageUrls);
			};

			downloadManager.download(downloadFile, serverFileHash, sources, serverFileSize, successCallback, failureCallback);
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

		Map<String, String> hashesToRefresh = failedDownloads.keySet().stream()
				.collect(Collectors.toMap(item -> item.file, item -> item.sha1, (first, second) -> first, LinkedHashMap::new));
		if (hashesToRefresh.isEmpty()) return false;
		LOGGER.warn("Remote acquisition failed for {} files after all sources; requesting one full regeneration", hashesToRefresh.size());
		byte[][] hashesArray = hashesToRefresh.values().stream().map(value -> value.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
		var refreshedContentOptional = ModpackUtils.refreshServerModpackContent(downloadClient, hashesArray);
		if (refreshedContentOptional.isEmpty()) {
			LOGGER.error("Failed to refresh the modpack content");
			return false;
		}

		SelectedModpackTarget refreshedTarget = SelectedModpackTarget.prepare(refreshedContentOptional.get(), selectedTarget.expectedPriorIntent(),
				selectedTarget.selection().intent(), selectedTarget.platform());
		Jsons.ModpackContentFields refreshedContent = refreshedTarget.flatTarget();
		if (!Objects.equals(serverModpackContent.modpackId, refreshedContent.modpackId)) {
			LOGGER.error("Refreshed catalogue changed modpack ID from {} to {}", serverModpackContent.modpackId, refreshedContent.modpackId);
			return false;
		}
		this.selectedTarget = refreshedTarget;
		this.serverModpackContent = refreshedContent;
		failedDownloads.clear();
		failedDownloadCategories.clear();
		totalBytesToDownload = 0;

		populateStoreFromModpack(refreshedContent.list, cache);
		ModpackUtils.populateStoreFromCWD(refreshedContent.list, cache);
		Set<Jsons.ModpackContentFields.ModpackContentItem> refreshedFilesToAcquire = ModpackUtils.identifyUncachedFiles(refreshedContent.list, cache);
		if (refreshedFilesToAcquire.isEmpty()) return true;

		List<FetchManager.FetchData> refreshedFetchData = new ArrayList<>();
		for (var item : refreshedFilesToAcquire) {
			totalBytesToDownload += Long.parseLong(item.size);
			if (item.type.equals("mod") || item.type.equals("shader") || item.type.equals("resourcepack"))
				refreshedFetchData.add(new FetchManager.FetchData(item.file, item.sha1, item.murmur, item.size, item.type));
		}
		FetchManager refreshedFetchManager = null;
		if (!refreshedFetchData.isEmpty()) {
			refreshedFetchManager = new FetchManager(refreshedFetchData);
			new ScreenManager().fetch(refreshedFetchManager);
			refreshedFetchManager.fetch();
		}

		downloadManager = new DownloadManager(totalBytesToDownload);
		new ScreenManager().download(downloadManager, getModpackName());
		downloadManager.attachDownloadClient(downloadClient);

		for (var serverItem : refreshedFilesToAcquire) {
			Path downloadFile = SmartFileUtils.getPath(modpackDir, serverItem.file);
			List<DownloadSource> sources = refreshedFetchManager != null && refreshedFetchManager.getFetchDatas().containsKey(serverItem.sha1)
					? refreshedFetchManager.getFetchDatas().get(serverItem.sha1).fetchedData().sources()
					: List.of();
			Consumer<DownloadManager.FailureCategory> failureCallback = category -> {
				failedDownloads.put(serverItem, sources.stream().map(DownloadSource::url).toList());
				failedDownloadCategories.put(serverItem, category);
			};
			Runnable successCallback = () -> changelogs.changesAddedList.put(downloadFile.getFileName().toString(), null);
			downloadManager.download(downloadFile, serverItem.sha1, sources, Long.parseLong(serverItem.size), successCallback, failureCallback);
		}
		downloadManager.joinAll();
		if (downloadManager.isCancelled()) {
			LOGGER.warn("Download canceled after regeneration");
			return false;
		}
		downloadManager.cancelAllAndShutdown();
		LOGGER.info("Finished full refreshed acquisition in {}ms", System.currentTimeMillis() - startFetching);
		return failedDownloads.isEmpty();
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
		executePlan(prepared.plan(), target);
		UpdatePlan plan = prepared.plan();

		EnumSet<RestartReason> restartReasons = plan.restartReasons().stream().map(reason -> RestartReason.valueOf(reason.name()))
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(RestartReason.class)));
		ApplyResult result = new ApplyResult(restartReasons);
		if (result.requiresRestart()) LOGGER.info("Restart required because: {}", String.join(", ", result.reasonDescriptions()));
		return result;
	}

	private PreparedPlan buildPlan(FileMetadataCache cache, ModFileCache modCache, Jsons.ModpackContentFields target) throws Exception {
		return buildPlan(cache, modCache, target, true);
	}

	private PreparedPlan buildPlan(FileMetadataCache cache, ModFileCache modCache, Jsons.ModpackContentFields target, boolean prepareObjects) throws Exception {
		Jsons.ModpackContentFields installed = ModpackContentTools.read(modpackContentFile);
		UpdatePlanner.SelectionContext selection = selectionContext(target.modpackId);
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(target, installed, selection, cache);
		if (prepareObjects) populateSelectionObjects(selection, target, files);
		Set<String> forceCopyServices = getForceCopyMods(target).stream().map(UpdatePlanner::normalize).collect(Collectors.toSet());
		List<UpdatePlan.ModInfo> targetMods = inspectTargetMods(target, cache, modCache);
		List<UpdatePlan.ModInfo> standardMods = inspectStandardMods(cache, modCache);
		List<UpdatePlan.NestedCopy> nestedCopies = prepareObjects ? inspectNestedCopies(target, cache) : List.of();
		Jsons.ClientConfigFieldsV3 plannedConfig = ModpackUtils.planModpackSelection(target.modpackId, modpackDir, connectionInfo);

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target, files, forceCopyServices, targetMods, standardMods, nestedCopies, selection, plannedConfig));
		if (!LauncherVersionSwapper.requiresLoaderVersionSwap(target.loader, target.loaderVersion)) return new PreparedPlan(plan, files);
		Set<UpdatePlan.RestartReason> restartReasons = EnumSet.noneOf(UpdatePlan.RestartReason.class);
		restartReasons.addAll(plan.restartReasons());
		restartReasons.add(UpdatePlan.RestartReason.CHANGED_LOADER_VERSION);
		UpdatePlan withLoaderRestart = new UpdatePlan(plan.modpackId(), plan.generationTarget(), plan.operations(), plan.projectedFinalState(), plan.plannedClientConfig(),
				restartReasons, plan.preservations(), plan.baselineCaptures());
		return new PreparedPlan(withLoaderRestart, files);
	}

	private boolean requestUpdatePreview(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate) throws Exception {
		if (selectedTarget == null) throw new IllegalStateException("Selected modpack target is unavailable");
		try (var cache = FileMetadataCache.open(hashCacheDBFile); var modCache = ModFileCache.open(modCacheDBFile)) {
			PreparedPlan prepared = buildPlan(cache, modCache, selectedTarget.flatTarget(), false);
			return requestPreparedPlanPreview(prepared, () -> startUpdateAfterPreview(filesToUpdate, prepared), () -> {
				close();
				if (firstConnection) new ScreenManager().title();
			});
		}
	}

	private boolean requestPreparedPlanPreview(PreparedPlan prepared, Runnable continueAction, Runnable cancelAction) throws IOException {
		UpdatePreview preview = UpdatePreview.create(prepared.plan(), prepared.originalFiles(), selectedTarget.flatTarget(), selectedTarget.selection(), false, getPatchNotes());
		return new ScreenManager().preview(preview, getModpackName(),
				(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(continueAction), cancelAction);
	}

	private UpdatePlanner.SelectionContext selectionContext(String targetModpackId) {
		String previousId = clientConfig.selectedModpackId;
		if (previousId == null || previousId.isBlank() || previousId.equals(targetModpackId) || !ModpackId.isValid(previousId)) return null;
		Path previousDirectory = ModpackUtils.getModpackPath(previousId);
		Jsons.ModpackContentFields previousManifest = ModpackContentTools.read(previousDirectory.resolve(modpackContentFileName));
		return new UpdatePlanner.SelectionContext(previousId, previousManifest);
	}

	private void populateSelectionObjects(UpdatePlanner.SelectionContext selection, Jsons.ModpackContentFields target,
			Map<UpdatePlan.FileKey, UpdatePlan.FileState> files) throws IOException {
		if (selection == null) return;
		if (selection.previousManifest() != null && selection.previousManifest().list != null) for (var item : selection.previousManifest().list) {
			if (!item.editable) continue;
			UpdatePlan.FileKey key = "mod".equals(item.type)
					? new UpdatePlan.FileKey(UpdatePlan.Root.MODS_DIR, Path.of(UpdatePlanner.normalize(item.file)).getFileName().toString())
					: new UpdatePlan.FileKey(UpdatePlan.Root.GAME_DIR, UpdatePlanner.normalize(item.file));
			UpdatePlan.FileState state = files.get(key);
			if (state == null || state.sha1() == null) continue;
			Path source = key.root() == UpdatePlan.Root.MODS_DIR ? MODS_DIR.resolve(key.relativePath()) : SmartFileUtils.CWD.resolve(key.relativePath());
			populateSelectionObject(source, state);
		}
		for (var item : target.list) {
			if (!item.editable) continue;
			UpdatePlan.FileKey key = new UpdatePlan.FileKey(UpdatePlan.Root.MODPACK_DIR, UpdatePlanner.normalize(item.file));
			UpdatePlan.FileState state = files.get(key);
			if (state != null && state.sha1() != null) populateSelectionObject(modpackDir.resolve(key.relativePath()), state);
		}
	}

	private void populateSelectionObject(Path source, UpdatePlan.FileState state) throws IOException {
		Path storeFile = clientGenerationObjectsDir.resolve(state.sha1());
		if (!SmartFileUtils.isValidFile(storeFile, state.size(), state.sha1()))
			SmartFileUtils.copyVerifiedAtomic(source, storeFile, state.size(), state.sha1());
	}

	private Map<UpdatePlan.FileKey, UpdatePlan.FileState> inspectFiles(Jsons.ModpackContentFields target, Jsons.ModpackContentFields installed,
			UpdatePlanner.SelectionContext selection, FileMetadataCache cache) throws IOException {
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = new HashMap<>();
		if (Files.isDirectory(modpackDir)) {
			try (Stream<Path> stream = Files.walk(modpackDir)) {
				Path installedManifest = modpackDir.resolve(modpackContentFileName);
				Path completeCatalogue = modpackDir.resolve(modpackCatalogueFileName);
				Path baselineManifest = modpackDir.resolve(modpackBaselineFileName);
				for (Path path : stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
						.filter(path -> !path.equals(installedManifest) && !path.equals(completeCatalogue) && !path.equals(baselineManifest)).toList())
					putFileState(files, UpdatePlan.Root.MODPACK_DIR, modpackDir, path, cache);
			}
		}
		if (Files.isDirectory(MODS_DIR)) {
			try (Stream<Path> stream = Files.list(MODS_DIR)) {
				for (Path path : stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) putFileState(files, UpdatePlan.Root.MODS_DIR, MODS_DIR, path, cache);
			}
		}
		Set<String> gamePaths = new HashSet<>();
		if (target.list != null) target.list.stream().filter(item -> !"mod".equals(item.type)).forEach(item -> gamePaths.add(item.file));
		if (installed != null && installed.list != null) installed.list.stream().filter(item -> !"mod".equals(item.type)).forEach(item -> gamePaths.add(item.file));
		if (selection != null && selection.previousManifest() != null && selection.previousManifest().list != null)
			selection.previousManifest().list.stream().filter(item -> !"mod".equals(item.type)).forEach(item -> gamePaths.add(item.file));
		for (String gamePath : gamePaths) {
			Path path = SmartFileUtils.getPathFromCWD(gamePath);
			if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) putFileState(files, UpdatePlan.Root.GAME_DIR, SmartFileUtils.CWD, path, cache);
		}
		OwnershipLedger ledger = OwnershipLedger.fromFields(target.ownershipLedger);
		for (String logicalPath : ledger.entries().keySet()) {
			Optional<UpdatePlan.FileKey> cleanupKey = UpdatePlanner.managedCleanupKey(logicalPath);
			if (cleanupKey.isEmpty()) continue;
			UpdatePlan.FileKey key = cleanupKey.get();
			Path root = key.root() == UpdatePlan.Root.MODS_DIR ? MODS_DIR : SmartFileUtils.CWD;
			Path path = root.resolve(key.relativePath()).normalize();
			if (path.startsWith(root) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) putFileState(files, key.root(), root, path, cache);
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
			Path source = clientGenerationObjectsDir.resolve(item.sha1);
			if (!SmartFileUtils.isValidFile(source, size, item.sha1)) source = SmartFileUtils.getPath(modpackDir, item.file);
			if (!SmartFileUtils.isValidFile(source, size, item.sha1)) continue;
			FileInspection.Mod mod = modCache.getModOrNull(source, cache);
			if (mod != null) mods.add(new UpdatePlan.ModInfo(UpdatePlanner.normalize(item.file), item.sha1, size, mod.IDs(), mod.deps()));
		}
		return mods;
	}

	private List<UpdatePlan.ModInfo> inspectStandardMods(FileMetadataCache cache, ModFileCache modCache) throws IOException {
		if (!Files.isDirectory(MODS_DIR)) return List.of();
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		try (Stream<Path> stream = Files.list(MODS_DIR)) {
			for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
				FileInspection.Mod mod = modCache.getModOrNull(path, cache);
				if (mod != null) mods.add(new UpdatePlan.ModInfo(path.getFileName().toString(), mod.hash(), Files.size(path), mod.IDs(), mod.deps()));
			}
		}
		return mods;
	}

	private List<UpdatePlan.NestedCopy> inspectNestedCopies(Jsons.ModpackContentFields target, FileMetadataCache cache) throws IOException {
		Files.createDirectories(cacheDir);
		Path inspectionDirectory = Files.createTempDirectory(cacheDir, "update-inspection-");
		try {
			Path inspectionMods = inspectionDirectory.resolve("mods");
			Files.createDirectories(inspectionMods);
			for (var item : target.list.stream().filter(value -> "mod".equals(value.type)).toList()) {
				Path source = clientGenerationObjectsDir.resolve(item.sha1);
				if (!SmartFileUtils.isValidFile(source, Long.parseLong(item.size), item.sha1)) source = SmartFileUtils.getPath(modpackDir, item.file);
				if (!SmartFileUtils.isValidFile(source, Long.parseLong(item.size), item.sha1)) continue;
				SmartFileUtils.copyVerifiedAtomic(source, inspectionMods.resolve(Path.of(UpdatePlanner.normalize(item.file)).getFileName()), Long.parseLong(item.size),
						item.sha1);
			}

			List<UpdatePlan.NestedCopy> copies = new ArrayList<>();
			for (FileInspection.Mod mod : MODPACK_LOADER.getModpackNestedConflicts(inspectionDirectory, cache)) {
				if (mod.path() == null || mod.hash() == null || !Files.isRegularFile(mod.path())) continue;
				long size = Files.size(mod.path());
				Path storeFile = clientGenerationObjectsDir.resolve(mod.hash());
				if (!SmartFileUtils.isValidFile(storeFile, size, mod.hash())) SmartFileUtils.copyVerifiedAtomic(mod.path(), storeFile, size, mod.hash());
				copies.add(new UpdatePlan.NestedCopy(mod.path().getFileName().toString(), mod.hash(), size, mod.IDs()));
			}
			return copies;
		} finally {
			try (Stream<Path> stream = Files.walk(inspectionDirectory)) {
				for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}

	private void executePlan(UpdatePlan plan, SelectedModpackTarget target) throws IOException {
		ensurePlanObjects(plan, target.flatTarget());
		UpdateTransaction transaction = UpdateTransaction.create(plan, target, modpackDir);
		UpdateTransactionExecutor.Execution execution = UpdateTransactionSupport.executor(transaction).commit(transaction);
		if (!execution.success()) {
			DetachedUpdateHelper.launch(transaction);
			throw new UpdateDeferredException(transaction.transactionId, execution.blockedPath(), execution.message());
		}
		clientConfig = plan.plannedClientConfig();
		try {
			ClientContentHistory.record(modpackDir.resolve(modpackHistoryFileName), target.flatTarget(), target.selection(), getPatchNotes());
		} catch (IOException e) {
			LOGGER.warn("Modpack update succeeded, but client content history could not be written", e);
		}
		collectClientObjects();
	}

	private void collectClientObjects() {
		if (Files.exists(SmartFileUtils.CWD.resolve(transactionFile))) {
			LOGGER.warn("Skipping client object collection while an update transaction is pending");
			return;
		}

		try (var hashCache = FileMetadataCache.open(hashCacheDBFile); var modCache = ModFileCache.open(modCacheDBFile)) {
			Set<String> retainedHashes = new HashSet<>();
			if (Files.isDirectory(modpacksDir)) {
				try (Stream<Path> modpackDirectories = Files.list(modpacksDir)) {
					for (Path modpackDirectory : modpackDirectories.filter(Files::isDirectory).toList())
						collectModpackObjectReferences(modpackDirectory, retainedHashes);
				}
			}
			if (MODS_DIR != null && Files.isDirectory(MODS_DIR)) {
				try (Stream<Path> standardMods = Files.list(MODS_DIR)) {
					for (Path mod : standardMods.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).filter(path -> path.toString().endsWith(".jar")).toList()) {
						String hash = hashCache.getHashOrNull(mod);
						if (hash != null) retainedHashes.add(ClientObjectStore.normalizeHash(hash));
					}
				}
			}

			ClientObjectStore.CollectionResult result = new ClientObjectStore(SmartFileUtils.CWD.resolve(clientGenerationObjectsDir),
					SmartFileUtils.CWD.resolve(clientGenerationStagingDir)).collect(retainedHashes);
			hashCache.cleanup();
			modCache.retainOnly(retainedHashes);
			if (result.objectsDeleted() > 0 || result.stagingFilesDeleted() > 0)
				LOGGER.info("Collected {} unreachable client objects and {} abandoned staging files ({} objects retained)", result.objectsDeleted(),
						result.stagingFilesDeleted(), result.objectsAfter());
		} catch (Exception e) {
			LOGGER.warn("Client update succeeded, but client object collection was skipped", e);
		}
	}

	private static void collectModpackObjectReferences(Path modpackDirectory, Set<String> retainedHashes) throws IOException {
		Jsons.ModpackContentFields installed = ModpackContentTools.read(modpackDirectory.resolve(modpackContentFileName));
		Jsons.CompleteModpackContentFields complete = ModpackContentTools.readCompleteFields(modpackDirectory.resolve(modpackCatalogueFileName));
		if (installed == null || complete == null) {
			try (Stream<Path> files = Files.walk(modpackDirectory)) {
				if (installed == null && complete == null && files.noneMatch(Files::isRegularFile)) return;
			}
			throw new IOException("Client modpack metadata is incomplete: " + modpackDirectory);
		}
		for (var item : installed.list) if (item != null && item.sha1 != null) retainedHashes.add(ClientObjectStore.normalizeHash(item.sha1));

		GenerationRecord record = GenerationRecord.fromFields(complete);
		for (var group : record.manifest().groups().values())
			for (var file : group.files().values()) retainedHashes.add(ClientObjectStore.normalizeHash(file.sha1()));
		for (var entry : record.ownershipLedger().entries().values())
			for (var content : entry.historicalHashes()) retainedHashes.add(ClientObjectStore.normalizeHash(content.sha1()));

		Jsons.ClientBaselineFields baseline = ConfigTools.read(modpackDirectory.resolve(modpackBaselineFileName), Jsons.ClientBaselineFields.class).orElse(null);
		if (baseline != null && baseline.entries != null)
			for (var entry : baseline.entries)
				if (entry != null && !entry.absent && entry.objectHash != null) retainedHashes.add(ClientObjectStore.normalizeHash(entry.objectHash));
	}

	private void populateStoreFromModpack(Collection<Jsons.ModpackContentFields.ModpackContentItem> items, FileMetadataCache cache) {
		for (var item : items) {
			Path storeFile = clientGenerationObjectsDir.resolve(item.sha1);
			long size = Long.parseLong(item.size);
			if (SmartFileUtils.isValidFile(storeFile, size, item.sha1)) continue;
			Path source = SmartFileUtils.getPath(modpackDir, item.file);
			if (!SmartFileUtils.isValidFile(source, size, item.sha1)) continue;
			try {
				SmartFileUtils.copyVerifiedAtomic(source, storeFile, size, item.sha1);
				cache.overwriteCache(storeFile, item.sha1);
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
			Path storeFile = clientGenerationObjectsDir.resolve(operation.expectedObjectHash());
			if (SmartFileUtils.isValidFile(storeFile, operation.expectedSize(), operation.expectedObjectHash())) continue;
			var item = itemsByHash.get(operation.expectedObjectHash().toLowerCase(Locale.ROOT));
			if (item == null) throw new IOException("Planned CAS object is unavailable: " + operation.expectedObjectHash());
			Path source = SmartFileUtils.getPath(modpackDir, item.file);
			if (!SmartFileUtils.isValidFile(source, operation.expectedSize(), operation.expectedObjectHash()))
				source = "mod".equals(item.type)
						? MODS_DIR.resolve(Path.of(UpdatePlanner.normalize(item.file)).getFileName())
						: SmartFileUtils.getPathFromCWD(item.file);
			if (!SmartFileUtils.isValidFile(source, operation.expectedSize(), operation.expectedObjectHash()))
				throw new IOException("Required object is absent from CAS and verified live locations: " + operation.expectedObjectHash());
			SmartFileUtils.copyVerifiedAtomic(source, storeFile, operation.expectedSize(), operation.expectedObjectHash());
		}
	}

	private boolean beginConfirmation() {
		if (!confirmationState.compareAndSet(ConfirmationState.INACTIVE, ConfirmationState.WAITING)) return false;
		confirmationExpiry = CONFIRMATION_TIMER.schedule(() -> {
			if (!confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.EXPIRED)) return;
			close();
		}, CONFIRMATION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		return true;
	}

	private void cancelConfirmationExpiry() {
		ScheduledFuture<?> expiry = confirmationExpiry;
		if (expiry != null) expiry.cancel(false);
	}

	@Override
	public void close() {
		confirmationState.compareAndSet(ConfirmationState.WAITING, ConfirmationState.CANCELLED);
		cancelConfirmationExpiry();
		if (closed.compareAndSet(false, true) && downloadClient != null) downloadClient.close();
	}

	public enum ConfirmationState {
		INACTIVE, WAITING, STARTED, CANCELLED, EXPIRED
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

	private record PreparedPlan(UpdatePlan plan, Map<UpdatePlan.FileKey, UpdatePlan.FileState> originalFiles) {
		private PreparedPlan {
			originalFiles = Map.copyOf(originalFiles);
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

	// Returns the modpack mods that ship a service file this loader's running version cannot host
	// in place (see ModpackLoaderService#forceCopyServices) - these must be copied into standard
	// mods/ instead of staying in the modpack folder.
	private Set<String> getForceCopyMods(Jsons.ModpackContentFields modpackContentFields) throws IOException {
		Set<String> forceCopyServices = MODPACK_LOADER.forceCopyServices();
		Set<String> forceCopyMods = new HashSet<>();
		if (forceCopyServices.isEmpty()) return forceCopyMods;

		for (Jsons.ModpackContentFields.ModpackContentItem item : modpackContentFields.list) {
			if (!item.type.equals("mod")) continue;

			long size = Long.parseLong(item.size);
			Path modPath = clientGenerationObjectsDir.resolve(item.sha1);
			if (!SmartFileUtils.isValidFile(modPath, size, item.sha1)) modPath = SmartFileUtils.getPath(modpackDir, item.file);
			if (!SmartFileUtils.isValidFile(modPath, size, item.sha1)) continue;
			try (FileSystem fs = FileSystems.newFileSystem(modPath)) {
				if (!FileInspection.getServices(fs, forceCopyServices).isEmpty()) forceCopyMods.add(item.file);
			}
		}

		return forceCopyMods;
	}
}
