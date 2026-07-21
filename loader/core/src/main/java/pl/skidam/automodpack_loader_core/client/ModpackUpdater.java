package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePlanner;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.LegacyClientCacheUtils;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.launchers.LauncherVersionSwapper;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

// TODO: clean up this mess
public class ModpackUpdater {
	public Changelogs changelogs = new Changelogs();
	public DownloadManager downloadManager;
	public long totalBytesToDownload = 0;
	public boolean fullDownload = false;
	private Jsons.ModpackContentFields serverModpackContent;
	public Map<Jsons.ModpackContentFields.ModpackContentItem, List<String>> failedDownloads = new HashMap<>();
	private final Jsons.ConnectionInfo connectionInfo;
	private final Secrets.Secret modpackSecret;
	private final UpdateLoopDetector updateLoopDetector = new UpdateLoopDetector();
	private Path modpackDir;
	private Path modpackContentFile;

	public String getModpackName() {
		return serverModpackContent.modpackName;
	}

	public Set<Jsons.ModpackContentFields.ModpackContentItem> getModpackFileList() {
		return serverModpackContent.list;
	}

	public ModpackUpdater(Jsons.ModpackContentFields modpackContent, Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret, Path modpackPath) {
		this.serverModpackContent = modpackContent;
		this.connectionInfo = connectionInfo;
		this.modpackSecret = secret;
		this.modpackDir = modpackPath;

		if (this.connectionInfo == null || !this.connectionInfo.isComplete()) throw new IllegalArgumentException("connectionInfo is null or empty");
	}

	public void processModpackUpdate(ModpackUtils.UpdateCheckResult result) {
		try {
			modpackContentFile = modpackDir.resolve(hostModpackContentFile.getFileName());

			// Handle the case where serverModpackContent is null
			if (serverModpackContent == null) {
				try (var cache = FileMetadataCache.open(hashCacheDBFile)) {
					checkAndLoadModpack(cache);
				}
				return;
			}

			// Create directories if they don't exist
			if (!Files.exists(modpackDir)) Files.createDirectories(modpackDir);

			// Handle new modpack
			if (!Files.exists(modpackContentFile)) {
				if (preload) {
					startUpdate(serverModpackContent.list);
				} else {
					fullDownload = true;
					new ScreenManager().danger(new ScreenManager().getScreen().orElseThrow(), this);
				}
			} else {
				// Handle existing modpack
				if (result == null) result = ModpackUtils.isUpdate(serverModpackContent, modpackDir);

				// Update or load the modpack
				if (result.requiresUpdate()) {
					startUpdate(result.filesToUpdate());
				} else {
					ModpackContentTools.write(modpackContentFile, serverModpackContent);
					try (var cache = FileMetadataCache.open(hashCacheDBFile)) {
						checkAndLoadModpack(cache);
					}
				}
			}
		} catch (Exception e) {
			LOGGER.error("Error while initializing modpack updater", e);
		}
	}

	public void checkAndLoadModpack() throws Exception {
		try (var cache = FileMetadataCache.open(hashCacheDBFile)) {
			checkAndLoadModpack(cache);
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

	private void checkAndLoadModpack(FileMetadataCache cache) throws Exception {
		if (!Files.exists(modpackDir)) return;

		Jsons.ModpackContentFields modpackContent = ModpackContentTools.read(modpackContentFile);
		if (modpackContent == null) throw new IllegalStateException("Failed to load modpack content");
		checkAndLoadModpack(cache, modpackContent);
	}

	private void checkAndLoadModpack(FileMetadataCache cache, Jsons.ModpackContentFields modpackContent) throws Exception {
		ApplyResult applyResult = applyModpack(cache, modpackContent);
		finishApplyingModpack(cache, applyResult);
	}

	private void finishApplyingModpack(FileMetadataCache cache, ApplyResult applyResult) throws Exception {
		if (applyResult.requiresRestart()) {
			String fingerprint = updateStateFingerprint(applyResult);
			if (updateLoopDetector.evaluateAndRecord(fingerprint) == UpdateLoopDetector.Decision.SUPPRESS) {
				LOGGER.error("Automatic restart loop detected. AutoModpack already requested two rapid restarts for the same correction state.");
				LOGGER.error("Corrections were applied during this launch but still require a restart: {}",
						String.join(", ", applyResult.reasonDescriptions()));
				LOGGER.error(
						"Another automatic restart was suppressed. The modpack may not be fully active; inspect the surrounding logs and report recurring issues at https://github.com/Skidamek/AutoModpack/issues");
				return;
			}

			LOGGER.info("Modpack is not loaded");
			UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
			new ReLauncher(modpackDir, updateType, changelogs).restart(true);
			return;
		}

		updateLoopDetector.clear();
		loadModpackMods(cache);
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
		if (modpackSecret == null) {
			LOGGER.error("Cannot update modpack, secret is null");
			new ScreenManager().error("automodpack.error.critical", "Secret is null - cannot update", "automodpack.error.logs");
			return;
		}

		new ScreenManager().download(downloadManager, getModpackName());
		long start = System.currentTimeMillis();

		try (var cache = FileMetadataCache.open(hashCacheDBFile)) {
			// Don't download files which already exist
			ModpackUtils.populateStoreFromCWD(filesToUpdate, cache);
			var finalFilesToUpdate = ModpackUtils.identifyUncachedFiles(filesToUpdate);

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

			LegacyClientCacheUtils.deleteDummyFiles();

			ApplyResult applyResult = applyModpack(cache, serverModpackContent);
			LOGGER.info("Done, saving {}", modpackContentFile);
			ModpackContentTools.write(modpackContentFile, serverModpackContent);

			if (preload) {
				LOGGER.info("Update completed! Took: {}ms", System.currentTimeMillis() - start);
				finishApplyingModpack(cache, applyResult);
			} else {
				boolean requiredRestart = applyResult.requiresRestart();
				LOGGER.info("Update completed! Required restart: {} Took: {}ms", requiredRestart, System.currentTimeMillis() - start);
				UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
				new ReLauncher(modpackDir, updateType, changelogs).restart(false);
			}
		} catch (SocketTimeoutException | ConnectException e) {
			LOGGER.error("{} is not responding", "Modpack host of " + connectionInfo.endpoint.getHostString(), e);
		} catch (InterruptedException e) {
			LOGGER.info("Interrupted the download");
		} catch (Exception e) {
			new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
			LOGGER.error("Critical error during modpack update", e);
		}
	}

	private boolean downloadModpack(Set<Jsons.ModpackContentFields.ModpackContentItem> finalFilesToUpdate, long startFetching, @Nullable FetchManager fetchManager,
			FileMetadataCache cache) throws InterruptedException {
		int wholeQueue = finalFilesToUpdate.size();

		if (wholeQueue == 0) {
			LOGGER.info("No files to download.");
			return true;
		}

		LOGGER.info("In queue left {} files to download ({}MB)", wholeQueue, totalBytesToDownload / 1024 / 1024);

		DownloadClient downloadClient = DownloadClient.tryCreate(connectionInfo, modpackSecret.secretBytes(), Math.min(wholeQueue, 5),
				ModpackUtils.manualValidationCallback(connectionInfo, false));
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

			Runnable failureCallback = () -> failedDownloads.put(serverItem, sources.stream().map(DownloadSource::url).toList());

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

		Map<String, String> hashesToRefresh = new HashMap<>(); // File name, hash
		var failedDownloadsSecMap = new HashMap<>(failedDownloads);
		failedDownloadsSecMap.forEach((k, v) -> {
			hashesToRefresh.put(k.file, k.sha1);
			failedDownloads.remove(k);
			totalBytesToDownload += Long.parseLong(k.size);
		});

		if (hashesToRefresh.isEmpty()) return false;

		LOGGER.warn("Failed to download {} files", hashesToRefresh.size());

		// make byte[][] from hashesToRefresh.values()
		byte[][] hashesArray = hashesToRefresh.values().stream().map(String::getBytes).toArray(byte[][]::new);

		// send it to the server and get the new modpack content
		// TODO set client to a waiting for the server to respond screen
		LOGGER.warn("Trying to refresh the modpack content");
		LOGGER.info("Sending hashes to refresh: {}", hashesToRefresh.values());
		var refreshedContentOptional = ModpackUtils.refreshServerModpackContent(connectionInfo, modpackSecret, hashesArray, false);
		if (refreshedContentOptional.isEmpty()) {
			LOGGER.error("Failed to refresh the modpack content");
			failedDownloads.putAll(failedDownloadsSecMap);
			return false;
		} else {
			LOGGER.info("Successfully refreshed the modpack content");
			// retry the download
			// success
			// or fail and then show the error

			var refreshedContent = refreshedContentOptional.get();
			if (!Objects.equals(serverModpackContent.modpackId, refreshedContent.modpackId)) {
				LOGGER.error("Refreshed manifest changed modpack ID from {} to {}", serverModpackContent.modpackId, refreshedContent.modpackId);
				failedDownloads.putAll(failedDownloadsSecMap);
				return false;
			}
			this.serverModpackContent = refreshedContent;

			// filter list to only the failed downloads
			var refreshedFilteredList = refreshedContent.list.stream().filter(item -> hashesToRefresh.containsKey(item.file)).toList();
			if (refreshedFilteredList.isEmpty()) {
				failedDownloads.putAll(failedDownloadsSecMap);
				return false;
			}
			downloadClient = DownloadClient.tryCreate(connectionInfo, modpackSecret.secretBytes(), Math.min(refreshedFilteredList.size(), 5), ModpackUtils.manualValidationCallback(connectionInfo, false));
			if (downloadClient == null) {
				failedDownloads.putAll(failedDownloadsSecMap);
				return false;
			}

			downloadManager = new DownloadManager(totalBytesToDownload);
			new ScreenManager().download(downloadManager, getModpackName());
			downloadManager.attachDownloadClient(downloadClient);

			// TODO try to fetch again from modrinth and curseforge

			for (var serverItem : refreshedFilteredList) {

				String serverFilePath = serverItem.file;
				String serverFileHash = serverItem.sha1;
				long serverFileSize = Long.parseLong(serverItem.size);

				Path downloadFile = SmartFileUtils.getPath(modpackDir, serverFilePath);

				LOGGER.info("Retrying to download {} from {}", serverFilePath, connectionInfo.endpoint.getHostString());

				Runnable failureCallback = () -> failedDownloads.put(serverItem, List.of());

				Runnable successCallback = () -> changelogs.changesAddedList.put(downloadFile.getFileName().toString(), null);

				downloadManager.download(downloadFile, serverFileHash, List.of(), serverFileSize, successCallback, failureCallback);
			}

			downloadManager.joinAll();

			if (downloadManager.isCancelled()) {
				LOGGER.warn("Download canceled");
				return false;
			}

			downloadManager.cancelAllAndShutdown();

			LOGGER.info("Finished refreshed downloading files in {}ms", System.currentTimeMillis() - startFetching);
		}

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
	private ApplyResult applyModpack(FileMetadataCache cache, Jsons.ModpackContentFields modpackContent) throws Exception {
		UpdatePlan plan = buildPlan(cache, modpackContent);
		executePlan(plan, cache);

		EnumSet<RestartReason> restartReasons = plan.restartReasons().stream().map(reason -> RestartReason.valueOf(reason.name()))
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(RestartReason.class)));
		if (LauncherVersionSwapper.swapLoaderVersion(modpackContent.loader, modpackContent.loaderVersion)) restartReasons.add(RestartReason.CHANGED_LOADER_VERSION);
		ApplyResult result = new ApplyResult(restartReasons);
		if (result.requiresRestart()) LOGGER.info("Restart required because: {}", String.join(", ", result.reasonDescriptions()));
		return result;
	}

	private UpdatePlan buildPlan(FileMetadataCache cache, Jsons.ModpackContentFields target) throws Exception {
		Jsons.ModpackContentFields installed = ModpackContentTools.read(modpackContentFile);
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(target, installed, cache);
		Set<String> forceCopyServices = getForceCopyMods(target).stream().map(UpdatePlanner::normalize).collect(Collectors.toSet());
		List<UpdatePlan.ModInfo> targetMods = inspectTargetMods(target, cache);
		List<UpdatePlan.ModInfo> standardMods = inspectStandardMods(cache);
		List<UpdatePlan.NestedCopy> nestedCopies = inspectNestedCopies(cache);
		Jsons.ClientConfigFieldsV3 plannedConfig = ModpackUtils.planModpackSelection(target.modpackId, modpackDir, connectionInfo);

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, target, files, clientConfig.allowRemoteNonModpackDeletions,
				LegacyClientCacheUtils.getEvaluatedDeletionTimestamps(), forceCopyServices, targetMods, standardMods, nestedCopies, plannedConfig));
		reportPlanWarnings(plan.warnings());
		return plan;
	}

	private void reportPlanWarnings(List<UpdatePlan.Warning> warnings) {
		for (UpdatePlan.Warning warning : warnings) {
			switch (warning.type()) {
				case REMOTE_DELETION_DISABLED -> LOGGER.warn(
						"Server requested deletion of {} (sha1: {}), but remote non-modpack deletions are disabled; leaving it untouched",
						warning.requestedPath(), warning.expectedHash());
				case REMOTE_DELETION_HASH_MISMATCH -> LOGGER.warn(
						"Server-requested deletion of {} was not applied because {} has hash {} instead of {}; leaving it untouched",
						warning.requestedPath(), warning.actualPath() == null ? "no matching file" : warning.actualPath(),
						warning.actualHash() == null ? "none" : warning.actualHash(), warning.expectedHash());
			}
		}
	}

	private Map<UpdatePlan.FileKey, UpdatePlan.FileState> inspectFiles(Jsons.ModpackContentFields target, Jsons.ModpackContentFields installed,
			FileMetadataCache cache) throws IOException {
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = new HashMap<>();
		if (Files.isDirectory(modpackDir)) {
			try (Stream<Path> stream = Files.walk(modpackDir)) {
				for (Path path : stream.filter(Files::isRegularFile).filter(path -> !path.equals(modpackContentFile)).toList())
					putFileState(files, UpdatePlan.Root.MODPACK_DIR, modpackDir, path, cache);
			}
		}
		if (Files.isDirectory(MODS_DIR)) {
			try (Stream<Path> stream = Files.list(MODS_DIR)) {
				for (Path path : stream.filter(Files::isRegularFile).toList()) putFileState(files, UpdatePlan.Root.MODS_DIR, MODS_DIR, path, cache);
			}
		}
		Set<String> gamePaths = new HashSet<>();
		if (target.list != null) target.list.stream().filter(item -> !"mod".equals(item.type)).forEach(item -> gamePaths.add(item.file));
		if (installed != null && installed.list != null) installed.list.stream().filter(item -> !"mod".equals(item.type)).forEach(item -> gamePaths.add(item.file));
		for (String gamePath : gamePaths) {
			Path path = SmartFileUtils.getPathFromCWD(gamePath);
			if (Files.isRegularFile(path)) putFileState(files, UpdatePlan.Root.GAME_DIR, SmartFileUtils.CWD, path, cache);
		}
		if (target.nonModpackFilesToDelete != null) for (var request : target.nonModpackFilesToDelete) {
			Path requested = SmartFileUtils.getPathFromCWD(request.file);
			Path parent = Files.isDirectory(requested) ? requested : requested.getParent();
			if (parent == null || !Files.isDirectory(parent)) continue;
			try (Stream<Path> stream = Files.list(parent)) {
				for (Path path : stream.filter(Files::isRegularFile).toList()) {
					if (path.toAbsolutePath().normalize().startsWith(MODS_DIR.toAbsolutePath().normalize()))
						putFileState(files, UpdatePlan.Root.MODS_DIR, MODS_DIR, path, cache);
					else
						putFileState(files, UpdatePlan.Root.GAME_DIR, SmartFileUtils.CWD, path, cache);
				}
			}
		}
		return files;
	}

	private void putFileState(Map<UpdatePlan.FileKey, UpdatePlan.FileState> files, UpdatePlan.Root root, Path rootPath, Path path,
			FileMetadataCache cache) throws IOException {
		String relative = UpdatePlanner.normalize(rootPath.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString());
		files.put(new UpdatePlan.FileKey(root, relative), new UpdatePlan.FileState(cache.getHashOrNull(path), Files.size(path), true, FileInspection.isMod(path)));
	}

	private List<UpdatePlan.ModInfo> inspectTargetMods(Jsons.ModpackContentFields target, FileMetadataCache cache) {
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		for (var item : target.list.stream().filter(value -> "mod".equals(value.type)).sorted(Comparator.comparing(value -> value.file)).toList()) {
			Path source = storeDir.resolve(item.sha1);
			if (!SmartFileUtils.isValidFile(source, Long.parseLong(item.size), item.sha1)) source = SmartFileUtils.getPath(modpackDir, item.file);
			FileInspection.Mod mod = FileInspection.getMod(source, cache);
			if (mod != null) mods.add(new UpdatePlan.ModInfo(UpdatePlanner.normalize(item.file), item.sha1, Long.parseLong(item.size), mod.IDs(), mod.deps()));
		}
		return mods;
	}

	private List<UpdatePlan.ModInfo> inspectStandardMods(FileMetadataCache cache) throws IOException {
		if (!Files.isDirectory(MODS_DIR)) return List.of();
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		try (Stream<Path> stream = Files.list(MODS_DIR)) {
			for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
				FileInspection.Mod mod = FileInspection.getMod(path, cache);
				if (mod != null) mods.add(new UpdatePlan.ModInfo(path.getFileName().toString(), mod.hash(), Files.size(path), mod.IDs(), mod.deps()));
			}
		}
		return mods;
	}

	private List<UpdatePlan.NestedCopy> inspectNestedCopies(FileMetadataCache cache) throws IOException {
		List<UpdatePlan.NestedCopy> copies = new ArrayList<>();
		for (FileInspection.Mod mod : MODPACK_LOADER.getModpackNestedConflicts(modpackDir, cache)) {
			if (mod.path() == null || mod.hash() == null || !Files.isRegularFile(mod.path())) continue;
			long size = Files.size(mod.path());
			Path storeFile = storeDir.resolve(mod.hash());
			if (!SmartFileUtils.isValidFile(storeFile, size, mod.hash())) SmartFileUtils.copyVerifiedAtomic(mod.path(), storeFile, size, mod.hash());
			copies.add(new UpdatePlan.NestedCopy(mod.path().getFileName().toString(), mod.hash(), size, mod.IDs()));
		}
		return copies;
	}

	private void executePlan(UpdatePlan plan, FileMetadataCache cache) throws IOException {
		for (UpdatePlan.Operation operation : plan.operations()) {
			Path target = resolveOperation(operation);
			switch (operation.operation()) {
				case CREATE_DIRECTORY -> Files.createDirectories(target);
				case INSTALL_OBJECT -> {
					Path source = storeDir.resolve(operation.expectedObjectHash());
					SmartFileUtils.copyVerifiedAtomic(source, target, operation.expectedSize(), operation.expectedObjectHash());
					cache.overwriteCache(target, operation.expectedObjectHash());
				}
				case DELETE -> {
					if (operation.expectedExistingHash() != null && Files.exists(target)
							&& !operation.expectedExistingHash().equalsIgnoreCase(cache.getHashOrNull(target)))
						throw new IOException("Deletion target changed after planning: " + target);
					Files.deleteIfExists(target);
				}
				case REMOVE_EMPTY_DIRECTORY -> {
					if (SmartFileUtils.isEmptyDirectory(target)) Files.deleteIfExists(target);
				}
			}
		}
		ModpackUtils.persistPlannedClientConfig(plan.plannedClientConfig());
		for (String timestamp : plan.plannedDeletionTimestamps()) LegacyClientCacheUtils.markTimestampAsEvaluated(timestamp);
		if (!plan.plannedDeletionTimestamps().isEmpty()) LegacyClientCacheUtils.saveDeletedFilesTimestamps();
	}

	private Path resolveOperation(UpdatePlan.Operation operation) {
		Path root = switch (operation.root()) {
			case MODPACK_DIR -> modpackDir;
			case GAME_DIR -> SmartFileUtils.CWD;
			case MODS_DIR -> MODS_DIR;
			case STORE_DIR -> storeDir;
			case AUTOMODPACK_DIR -> automodpackDir;
		};
		Path resolved = root.resolve(UpdatePlanner.normalize(operation.relativePath())).normalize();
		if (!resolved.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new IllegalArgumentException("Operation escapes root");
		return resolved;
	}

	private enum RestartReason {
		REMOVED_NON_MODPACK_FILES("files removed from the modpack were deleted from the game directory"),
		CORRECTED_FILE_LOCATIONS("standard-directory mods were copied or updated"),
		FIXED_NESTED_MODS("conflicting nested mods were copied to the standard mods directory"),
		REMOVED_DUPLICATE_MODS("duplicate standard-directory mods were removed"),
		REMOVED_STANDARD_MODS("modpack-owned mods were removed from the standard mods directory"),
		APPLIED_SERVER_DELETIONS("server-requested mod deletions were applied"),
		CHANGED_LOADER_VERSION("launcher loader-version metadata changed");

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

	// Returns the modpack mods that ship a service file this loader's running version cannot host
	// in place (see ModpackLoaderService#forceCopyServices) - these must be copied into standard
	// mods/ instead of staying in the modpack folder.
	private Set<String> getForceCopyMods(Jsons.ModpackContentFields modpackContentFields) throws IOException {
		Set<String> forceCopyServices = MODPACK_LOADER.forceCopyServices();
		Set<String> forceCopyMods = new HashSet<>();
		if (forceCopyServices.isEmpty()) return forceCopyMods;

		for (Jsons.ModpackContentFields.ModpackContentItem item : modpackContentFields.list) {
			if (!item.type.equals("mod")) continue;

			Path modPath = storeDir.resolve(item.sha1);
			if (!SmartFileUtils.isValidFile(modPath, Long.parseLong(item.size), item.sha1)) modPath = SmartFileUtils.getPath(modpackDir, item.file);
			try (FileSystem fs = FileSystems.newFileSystem(modPath)) {
				if (!FileInspection.getServices(fs, forceCopyServices).isEmpty()) forceCopyMods.add(item.file);
			}
		}

		return forceCopyMods;
	}
}
