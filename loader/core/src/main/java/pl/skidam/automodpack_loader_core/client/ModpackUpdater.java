package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

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
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.LegacyClientCacheUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.UpdateLoopDetector;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
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
	private String serverModpackContentJson; // TODO: remove this variable and use serverModpackContent directly
	public Map<Jsons.ModpackContentFields.ModpackContentItem, List<String>> failedDownloads = new HashMap<>();
	private final Set<String> newDownloadedFiles = new HashSet<>(); // Only files which did not exist before. Because some files may have the same name/path and be updated.
	private final Set<String> overwrittenEditableFiles = new HashSet<>();
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

			// Prepare for modpack update
			serverModpackContentJson = GSON.toJson(serverModpackContent);

			// Create directories if they don't exist
			if (!Files.exists(modpackDir)) Files.createDirectories(modpackDir);

			// Handle new modpack
			if (!Files.exists(modpackContentFile)) {
				if (preload) {
					startUpdate(serverModpackContent.list, Set.of());
				} else {
					fullDownload = true;
					new ScreenManager().danger(new ScreenManager().getScreen().orElseThrow(), this);
				}
			} else {
				// Handle existing modpack
				if (result == null) result = ModpackUtils.isUpdate(serverModpackContent, modpackDir);

				// Update or load the modpack
				if (result.requiresUpdate()) {
					startUpdate(result.filesToUpdate(), result.changedOverwriteEditableFiles());
				} else {
					Files.writeString(modpackContentFile, serverModpackContentJson);
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

		Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);
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
		startUpdate(filesToUpdate, Set.of());
	}

	private void startUpdate(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate, Set<String> changedOverwriteEditableFiles) {
		if (modpackSecret == null) {
			LOGGER.error("Cannot update modpack, secret is null");
			new ScreenManager().error("automodpack.error.critical", "Secret is null - cannot update", "automodpack.error.logs");
			return;
		}

		overwrittenEditableFiles.addAll(changedOverwriteEditableFiles);

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
			Files.writeString(modpackContentFile, serverModpackContentJson);

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

			if (!Files.exists(downloadFile)) newDownloadedFiles.add(serverFilePath);

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

				try {
					cache.overwriteCache(downloadFile, serverFileHash);
				} catch (Exception e) {
					LOGGER.error("Failed to update cache for {}", downloadFile, e);
				}
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
			this.serverModpackContentJson = GSON.toJson(refreshedContent);

			// filter list to only the failed downloads
			var refreshedFilteredList = refreshedContent.list.stream().filter(item -> hashesToRefresh.containsKey(item.file)).toList();
			if (refreshedFilteredList.isEmpty()) {
				failedDownloads.putAll(failedDownloadsSecMap);
				return false;
			}
			Jsons.ModpackContentFields installedContent = ConfigTools.loadModpackContent(modpackContentFile);
			Set<String> changedOverwriteEditableFiles = ModpackUtils.findChangedOverwriteEditableFiles(refreshedFilteredList, installedContent);
			for (var item : refreshedFilteredList) {
				if (changedOverwriteEditableFiles.contains(item.file)) overwrittenEditableFiles.add(item.file);
				else overwrittenEditableFiles.remove(item.file);
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

				Runnable successCallback = () -> {
					changelogs.changesAddedList.put(downloadFile.getFileName().toString(), null);

					try {
						cache.overwriteCache(downloadFile, serverFileHash);
					} catch (Exception e) {
						LOGGER.error("Failed to update cache for {}", downloadFile, e);
					}
				};

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
		ModpackUtils.selectModpack(serverModpackContent.modpackId, serverModpackContent.modpackName, modpackDir, connectionInfo, newDownloadedFiles);

		ModpackUtils.hardlinkModpack(modpackDir, modpackContent, cache);

		// Prepare modpack, analyze nested mods
		List<FileInspection.Mod> conflictingNestedMods = MODPACK_LOADER.getModpackNestedConflicts(modpackDir, cache);

		// delete old deleted files from the server modpack
		boolean needsRestart0 = deleteNonModpackFiles(modpackContent, cache);

		Set<String> workaroundMods = getForceCopyMods(modpackContent);
		Set<String> filesNotToCopy = getFilesNotToCopy(modpackContent.list, workaroundMods);
		boolean needsRestart1 = ModpackUtils.correctFilesLocations(modpackDir, modpackContent, filesNotToCopy, cache);

		Set<Path> modpackMods = new HashSet<>();
		Collection<FileInspection.Mod> modpackModList = new ArrayList<>();
		Collection<FileInspection.Mod> standardModList = new ArrayList<>();
		boolean needsRestart2;
		Set<String> ignoredFiles;

		try (var modCache = ModFileCache.open(modCacheDBFile)) {
			Path modpackModsDir = modpackDir.resolve("mods");
			if (Files.exists(modpackModsDir)) {
				try (Stream<Path> stream = Files.list(modpackModsDir)) {
					stream.forEach(path -> {
						modpackMods.add(path);
						FileInspection.Mod mod = modCache.getModOrNull(path, cache);
						if (mod != null) modpackModList.add(mod);
					});
				}
			}

			Path standardModsDir = MODS_DIR;
			if (Files.exists(standardModsDir)) {
				try (Stream<Path> stream = Files.list(standardModsDir)) {
					stream.forEach(path -> {
						FileInspection.Mod mod = modCache.getModOrNull(path, cache);
						if (mod != null) standardModList.add(mod);
					});
				}
			}

			// Check if the conflicting mods still exits, they might have been deleted by methods above
			conflictingNestedMods = conflictingNestedMods.stream().filter(conflictingMod -> modpackMods.contains(conflictingMod.path())).toList();

			if (!conflictingNestedMods.isEmpty()) { LOGGER.warn("Found conflicting nested mods: {}", conflictingNestedMods); }

			needsRestart2 = ModpackUtils.fixNestedMods(conflictingNestedMods, standardModList, cache, modCache);
			ignoredFiles = ModpackUtils.getIgnoredFiles(conflictingNestedMods, workaroundMods);
		}

		Set<String> forceCopyFiles = modpackContent.list.stream().filter(item -> item.forceCopy).map(item -> item.file).collect(Collectors.toSet());

		// Remove duplicate mods
		ModpackUtils.RemoveDupeModsResult removeDupeModsResult = ModpackUtils.removeDupeMods(modpackDir, standardModList, modpackModList, ignoredFiles,
				workaroundMods, forceCopyFiles);
		boolean needsRestart3 = removeDupeModsResult.requiresRestart();

		// Remove rest of mods not for standard mods directory
		boolean needsRestart4 = ModpackUtils.removeRestModsNotToCopy(modpackContent, filesNotToCopy, removeDupeModsResult.modsToKeep(), cache);

		boolean needsRestart5 = ModpackUtils.deleteFilesMarkedForDeletionByTheServer(modpackContent.nonModpackFilesToDelete, cache);

		boolean needsRestart6 = LauncherVersionSwapper.swapLoaderVersion(modpackContent.loader, modpackContent.loaderVersion);

		EnumSet<RestartReason> restartReasons = EnumSet.noneOf(RestartReason.class);
		if (needsRestart0) restartReasons.add(RestartReason.REMOVED_NON_MODPACK_FILES);
		if (needsRestart1) restartReasons.add(RestartReason.CORRECTED_FILE_LOCATIONS);
		if (needsRestart2) restartReasons.add(RestartReason.FIXED_NESTED_MODS);
		if (needsRestart3) restartReasons.add(RestartReason.REMOVED_DUPLICATE_MODS);
		if (needsRestart4) restartReasons.add(RestartReason.REMOVED_STANDARD_MODS);
		if (needsRestart5) restartReasons.add(RestartReason.APPLIED_SERVER_DELETIONS);
		if (needsRestart6) restartReasons.add(RestartReason.CHANGED_LOADER_VERSION);

		ApplyResult result = new ApplyResult(restartReasons);
		if (result.requiresRestart()) LOGGER.info("Restart required because: {}", String.join(", ", result.reasonDescriptions()));
		return result;
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

			Path modPath = SmartFileUtils.getPath(modpackDir, item.file);
			try (FileSystem fs = FileSystems.newFileSystem(modPath)) {
				if (!FileInspection.getServices(fs, forceCopyServices).isEmpty()) forceCopyMods.add(item.file);
			}
		}

		return forceCopyMods;
	}

	// returns set of formated files which we should not copy to the cwd - let them stay in the modpack directory
	private Set<String> getFilesNotToCopy(Set<Jsons.ModpackContentFields.ModpackContentItem> modpackContentItems, Set<String> workaroundMods) {
		Set<String> filesNotToCopy = new HashSet<>();

		// Make list of files which we do not copy to the running directory
		for (Jsons.ModpackContentFields.ModpackContentItem item : modpackContentItems) {
			// We only want to copy editable file if its downloaded first time
			// So we add to ignored any other editable file
			if (item.editable && !newDownloadedFiles.contains(item.file) && !overwrittenEditableFiles.contains(item.file)) {
				filesNotToCopy.add(item.file);
				continue;
			}

			if (item.forceCopy) continue;

			// We only want to copy mods which need a workaround
			if (item.type.equals("mod") && !workaroundMods.contains(item.file)) filesNotToCopy.add(item.file);
		}

		return filesNotToCopy;
	}

	private boolean deleteNonModpackFiles(Jsons.ModpackContentFields modpackContent, FileMetadataCache cache) throws IOException {
		Set<String> modpackFiles = modpackContent.list.stream().map(modpackContentField -> modpackContentField.file).collect(Collectors.toSet());
		List<Path> pathList;
		try (Stream<Path> pathStream = Files.walk(modpackDir)) {
			pathList = pathStream.toList();
		}
		Set<Path> parentPaths = new HashSet<>();
		boolean needsRestart = false;

		for (Path path : pathList) {
			if (Files.isDirectory(path) || path.equals(modpackContentFile)) continue;

			String formattedFile = SmartFileUtils.formatPath(path, modpackDir);
			if (modpackFiles.contains(formattedFile)) continue;

			Path runPath = SmartFileUtils.getPathFromCWD(formattedFile);
			if (cache.fastHashCompare(path, runPath)) {
				LOGGER.info("Deleting {} and {}", path, runPath);
				parentPaths.add(runPath.getParent());
				SmartFileUtils.executeOrder66(runPath, false);
				needsRestart = true;
			} else {
				LOGGER.info("Deleting {}", path);
			}

			parentPaths.add(path.getParent());
			SmartFileUtils.executeOrder66(path, false);
			changelogs.changesDeletedList.put(path.getFileName().toString(), null);
		}

		LegacyClientCacheUtils.saveDummyFiles();

		// recursively delete empty directories
		for (Path parentPath : parentPaths) {
			deleteEmptyParentDirectoriesRecursively(parentPath);
		}

		return needsRestart;
	}

	private void deleteEmptyParentDirectoriesRecursively(Path directory) throws IOException {
		if (directory == null || !SmartFileUtils.isEmptyDirectory(directory)) return;

		LOGGER.info("Deleting empty directory {}", directory);
		SmartFileUtils.executeOrder66(directory);
		deleteEmptyParentDirectoriesRecursively(directory.getParent());
	}
}
