package pl.skidam.automodpack_loader_core.client;

import org.jetbrains.annotations.Nullable;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.ModpackConnectionInfo;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_core.utils.launchers.LauncherVersionSwapper;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

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
    private final ModpackConnectionInfo connectionInfo;
    private final Jsons.PersistedModpackConnection modpackConnection;
    private final IntFunction<DownloadClient> preferredDownloadClientFactory;
    private final CompletableFuture<Boolean> loginUpdateFuture = new CompletableFuture<>();
    private Path modpackDir;
    private Path modpackContentFile;

    public String getModpackName() {
        return serverModpackContent.modpackName;
    }

    public Set<Jsons.ModpackContentFields.ModpackContentItem> getModpackFileList() {
        return serverModpackContent.list;
    }

    public CompletableFuture<Boolean> getLoginUpdateFuture() {
        return loginUpdateFuture;
    }

    public void cancelPendingUpdate() {
        completeLoginUpdate(true);
    }

    public ModpackUpdater(Jsons.ModpackContentFields modpackContent, ModpackConnectionInfo connectionInfo, Path modpackPath) {
        this(modpackContent, connectionInfo, connectionInfo == null ? null : connectionInfo.toPersistedModpackConnection(), modpackPath, null);
    }

    public ModpackUpdater(Jsons.ModpackContentFields modpackContent, ModpackConnectionInfo connectionInfo, Jsons.PersistedModpackConnection modpackConnection, Path modpackPath) {
        this(modpackContent, connectionInfo, modpackConnection, modpackPath, null);
    }

    public ModpackUpdater(Jsons.ModpackContentFields modpackContent, ModpackConnectionInfo connectionInfo, Jsons.PersistedModpackConnection modpackConnection, Path modpackPath, IntFunction<DownloadClient> preferredDownloadClientFactory) {
        this.serverModpackContent = modpackContent;
        this.connectionInfo = connectionInfo;
        this.modpackConnection = modpackConnection;
        this.modpackDir = modpackPath;
        this.preferredDownloadClientFactory = preferredDownloadClientFactory;

        if (this.connectionInfo == null || !this.connectionInfo.isUsable()) {
            throw new IllegalArgumentException("connectionInfo is null or unusable");
        }
    }

    public void processModpackUpdate(ModpackUtils.UpdateCheckResult result) {
        try {
            modpackContentFile = modpackDir.resolve(hostModpackContentFile.getFileName());

            // Handle the case where serverModpackContent is null
            if (serverModpackContent == null) {
                try (var cache = FileMetadataCache.open(hashCacheDBFile)) {
                    CheckAndLoadModpack(cache);
                }
                completeLoginUpdate(false);
                return;
            }

            // Prepare for modpack update
            serverModpackContentJson = GSON.toJson(serverModpackContent);

            // Create directories if they don't exist
            if (!Files.exists(modpackDir)) {
                Files.createDirectories(modpackDir);
            }

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
                // Rename the modpack if the name has changed
                modpackDir = ModpackUtils.renameModpackDir(serverModpackContent, modpackDir);
                modpackContentFile = modpackDir.resolve(modpackContentFile.getFileName());

                if (result == null) {
                    result = ModpackUtils.isUpdate(serverModpackContent, modpackDir);
                }

                // Update or load the modpack
                if (result.requiresUpdate()) {
                    startUpdate(result.filesToUpdate());
                } else {
                    Files.writeString(modpackContentFile, serverModpackContentJson);
                    try (var cache = FileMetadataCache.open(hashCacheDBFile)) {
                        CheckAndLoadModpack(cache);
                    }
                    completeLoginUpdate(false);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error while initializing modpack updater", e);
            completeLoginUpdate(true);
        }
    }

    private void CheckAndLoadModpack(FileMetadataCache cache) throws Exception {
        if (!Files.exists(modpackDir))
            return;

        boolean requiresRestart = applyModpack(cache);

        if (requiresRestart) {
            LOGGER.info("Modpack is not loaded");
            UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
            new ReLauncher(modpackDir, updateType, changelogs).restart(true);
            return;
        }

        // Load the modpack excluding mods from standard mods directory without need to restart the game
        if (preload) {
            Set<String> standardModsHashes;
            List<Path> modpackMods = List.of();

            // 1. Collect hashes of existing standard mods into a Set for fast lookup
            try (Stream<Path> standardModsStream = Files.list(MODS_DIR)) {
                standardModsHashes = standardModsStream
                        .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".jar")) // Check extension/type before hashing
                        .map(cache::getHashOrNull)     // Safe wrapper for IOException
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()); // Use Set for O(1) performance
            } catch (IOException e) {
                LOGGER.error("Failed to list standard mods directory", e);
                standardModsHashes = Collections.emptySet();
            }

            // 2. Filter modpack mods excluding those already present in standard mods
            Path modpackModsDir = modpackDir.resolve("mods");
            if (Files.exists(modpackModsDir)) {
                try (Stream<Path> modpackModsStream = Files.list(modpackModsDir)) {
                    final Set<String> finalStandardModsHashes = standardModsHashes;
                    modpackMods = modpackModsStream
                            .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".jar"))
                            .filter(mod -> {
                                String modHash = cache.getHashOrNull(mod);
                                // Only load if hash is valid AND not found in standard set
                                return modHash != null && !finalStandardModsHashes.contains(modHash);
                            })
                            .toList();
                } catch (IOException e) {
                    LOGGER.error("Failed to list modpack mods directory", e);
                }
            }

            MODPACK_LOADER.loadModpack(modpackMods);
            return;
        }

        LOGGER.info("Modpack is already loaded");
    }

    public void startUpdate(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate) {
        new ScreenManager().download(downloadManager, getModpackName());
        long start = System.currentTimeMillis();


        try (var cache = FileMetadataCache.open(hashCacheDBFile)) {
            // Don't download files which already exist
            ModpackUtils.populateStoreFromCWD(filesToUpdate, cache);
            var finalFilesToUpdate = ModpackUtils.identifyUncachedFiles(filesToUpdate);

            // Rename modpack
            modpackDir = ModpackUtils.renameModpackDir(serverModpackContent, modpackDir);
            modpackContentFile = modpackDir.resolve(modpackContentFile.getFileName());

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
                downloadModpack(finalFilesToUpdate, startFetching, fetchManager, cache);

                LOGGER.info("Done, saving {}", modpackContentFile);

                // Downloads completed, save json files
                Files.writeString(modpackContentFile, serverModpackContentJson);
            } catch (Exception e) {
                if (downloadManager != null) downloadManager.cancelAllAndShutdown();
                throw e;
            }

            LegacyClientCacheUtils.deleteDummyFiles();

            if (!failedDownloads.isEmpty()) {
                StringBuilder failedFiles = new StringBuilder();
                for (var download : failedDownloads.entrySet()) {
                    var item = download.getKey();
                    var urls = download.getValue();
                    LOGGER.error("Failed to download: {} from {}", item.file, urls);
                    failedFiles.append(item.file);
                }

                new ScreenManager().error("automodpack.error.files", "Failed to download: " + failedFiles, "automodpack.error.logs");
                LOGGER.error("Update failed successfully! Try again! Took: {}ms", System.currentTimeMillis() - start);
                completeLoginUpdate(true);
            } else if (preload) {
                LOGGER.info("Update completed! Took: {}ms", System.currentTimeMillis() - start);
                CheckAndLoadModpack(cache);
                completeLoginUpdate(false);
            } else  {
                boolean requiredRestart = applyModpack(cache);
                LOGGER.info("Update completed! Required restart: {} Took: {}ms", requiredRestart, System.currentTimeMillis() - start);
                UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
                completeLoginUpdate(true);
                new ReLauncher(modpackDir, updateType, changelogs).restart(false);
            }
        } catch (SocketTimeoutException | ConnectException e) {
            LOGGER.error("{} is not responding", "Modpack host of " + describeModpackTarget(), e);
            completeLoginUpdate(true);
        } catch (InterruptedException e) {
            LOGGER.info("Interrupted the download");
            completeLoginUpdate(true);
        } catch (Exception e) {
            new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
            LOGGER.error("Critical error during modpack update", e);
            completeLoginUpdate(true);
        }
    }

    private void completeLoginUpdate(boolean disconnectRequired) {
        loginUpdateFuture.complete(disconnectRequired);
    }

    private void downloadModpack(Set<Jsons.ModpackContentFields.ModpackContentItem> finalFilesToUpdate, long startFetching, @Nullable FetchManager fetchManager, FileMetadataCache cache) throws InterruptedException {
        int wholeQueue = finalFilesToUpdate.size();

        if (wholeQueue == 0) {
            LOGGER.info("No files to download.");
            return;
        }

        LOGGER.info("In queue left {} files to download ({}MB)", wholeQueue, totalBytesToDownload / 1024 / 1024);

        DownloadClient downloadClient = ModpackUtils.createDownloadClient(
                connectionInfo,
                Math.min(wholeQueue, 5),
                false,
                preferredDownloadClientFactory
        );
        if (downloadClient == null) {
            return;
        }

        downloadManager = new DownloadManager(totalBytesToDownload);
        new ScreenManager().download(downloadManager, getModpackName());
        downloadManager.attachDownloadClient(downloadClient);

        for (var serverItem : finalFilesToUpdate) {

            String serverFilePath = serverItem.file;
            String serverFileHash = serverItem.sha1;
            long serverFileSize = Long.parseLong(serverItem.size);

            Path downloadFile = SmartFileUtils.getPath(modpackDir, serverFilePath);

            if (!Files.exists(downloadFile)) {
                newDownloadedFiles.add(serverFilePath);
            }

            List<String> urls = new ArrayList<>();
            if (fetchManager != null && fetchManager.getFetchDatas().containsKey(serverFileHash)) {
                urls.addAll(fetchManager.getFetchDatas().get(serverFileHash).fetchedData().urls());
            }

            Runnable failureCallback = () -> {
                failedDownloads.put(serverItem, urls);
            };

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


            downloadManager.download(downloadFile, serverFileHash, urls, serverFileSize, successCallback, failureCallback);
        }

        downloadManager.joinAll();

        LOGGER.info("Finished downloading files in {}ms", System.currentTimeMillis() - startFetching);

        if (downloadManager.isCancelled()) {
            LOGGER.warn("Download canceled");
            return;
        }

        downloadManager.cancelAllAndShutdown();
        totalBytesToDownload = 0;

        if (failedDownloads.isEmpty()) {
            return;
        }

        Map<String, String> hashesToRefresh = new HashMap<>(); // File name, hash
        var failedDownloadsSecMap = new HashMap<>(failedDownloads);
        failedDownloadsSecMap.forEach((k, v) -> {
            hashesToRefresh.put(k.file, k.sha1);
            failedDownloads.remove(k);
            totalBytesToDownload += Long.parseLong(k.size);
        });

        if (hashesToRefresh.isEmpty()) {
            return;
        }

        LOGGER.warn("Failed to download {} files", hashesToRefresh.size());

        // make byte[][] from hashesToRefresh.values()
        byte[][] hashesArray = hashesToRefresh.values().stream()
                .map(String::getBytes)
                .toArray(byte[][]::new);

        // send it to the server and get the new modpack content
        // TODO set client to a waiting for the server to respond screen
        LOGGER.warn("Trying to refresh the modpack content");
        LOGGER.info("Sending hashes to refresh: {}", hashesToRefresh.values());
        var refreshedContentOptional = ModpackUtils.refreshServerModpackContent(connectionInfo, hashesArray, false, preferredDownloadClientFactory);
        if (refreshedContentOptional.isEmpty()) {
            LOGGER.error("Failed to refresh the modpack content");
        } else {
            LOGGER.info("Successfully refreshed the modpack content");
            // retry the download
            // success
            // or fail and then show the error

            var refreshedContent = refreshedContentOptional.get();
            this.serverModpackContent = refreshedContent;
            this.serverModpackContentJson = GSON.toJson(refreshedContent);

            // filter list to only the failed downloads
            var refreshedFilteredList = refreshedContent.list.stream().filter(item -> hashesToRefresh.containsKey(item.file)).toList();
            if (refreshedFilteredList.isEmpty()) {
                return;
            }

            downloadClient = ModpackUtils.createDownloadClient(
                    connectionInfo,
                    Math.min(refreshedFilteredList.size(), 5),
                    false,
                    preferredDownloadClientFactory
            );
            if (downloadClient == null) {
                return;
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

                LOGGER.info("Retrying to download {} from {}", serverFilePath, describeModpackTarget());

                Runnable failureCallback = () -> {
                    failedDownloads.put(serverItem, List.of());
                };

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
                return;
            }

            downloadManager.cancelAllAndShutdown();

            LOGGER.info("Finished refreshed downloading files in {}ms", System.currentTimeMillis() - startFetching);
        }
    }

    // this is run every time we modpack is updated
    // returns true if restart is required

    private String describeModpackTarget() {
        return connectionInfo.describeTarget();
    }
    private boolean applyModpack(FileMetadataCache cache) throws Exception {
        ModpackUtils.selectModpack(modpackDir, modpackConnection, newDownloadedFiles);
        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

        if (modpackContent == null) {
            throw new IllegalStateException("Failed to load modpack content"); // Something gone very wrong...
        }

        ModpackUtils.hardlinkModpack(modpackDir, modpackContent, cache);

        // Prepare modpack, analyze nested mods
        List<FileInspection.Mod> conflictingNestedMods = MODPACK_LOADER.getModpackNestedConflicts(modpackDir, cache);

        // delete old deleted files from the server modpack
        boolean needsRestart0 = deleteNonModpackFiles(modpackContent, cache);

        Set<String> workaroundMods = new WorkaroundUtil(modpackDir).getWorkaroundMods(modpackContent);
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
                        if (mod != null) {
                            modpackModList.add(mod);
                        }
                    });
                }
            }

            Path standardModsDir = MODS_DIR;
            if (Files.exists(standardModsDir)) {
                try (Stream<Path> stream = Files.list(standardModsDir)) {
                    stream.forEach(path -> {
                        FileInspection.Mod mod = modCache.getModOrNull(path, cache);
                        if (mod != null) {
                            standardModList.add(mod);
                        }
                    });
                }
            }

            // Check if the conflicting mods still exits, they might have been deleted by methods above
            conflictingNestedMods = conflictingNestedMods.stream()
                    .filter(conflictingMod -> modpackMods.contains(conflictingMod.path()))
                    .toList();

            if (!conflictingNestedMods.isEmpty()) {
                LOGGER.warn("Found conflicting nested mods: {}", conflictingNestedMods);
            }

            needsRestart2 = ModpackUtils.fixNestedMods(conflictingNestedMods, standardModList, cache, modCache);
            ignoredFiles = ModpackUtils.getIgnoredFiles(conflictingNestedMods, workaroundMods);
        }

        Set<String> forceCopyFiles = modpackContent.list.stream()
                .filter(item -> item.forceCopy)
                .map(item -> item.file)
                .collect(Collectors.toSet());

        // Remove duplicate mods
        ModpackUtils.RemoveDupeModsResult removeDupeModsResult = ModpackUtils.removeDupeMods(modpackDir, standardModList, modpackModList, ignoredFiles, workaroundMods, forceCopyFiles);
        boolean needsRestart3 = removeDupeModsResult.requiresRestart();

        // Remove rest of mods not for standard mods directory
        boolean needsRestart4 = ModpackUtils.removeRestModsNotToCopy(modpackContent, filesNotToCopy, removeDupeModsResult.modsToKeep(), cache);

        boolean needsRestart5 = ModpackUtils.deleteFilesMarkedForDeletionByTheServer(modpackContent.nonModpackFilesToDelete, cache);

        boolean needsRestart6 = LauncherVersionSwapper.swapLoaderVersion(modpackContent.loader, modpackContent.loaderVersion);

        return needsRestart0 || needsRestart1 || needsRestart2 || needsRestart3 || needsRestart4 || needsRestart5 || needsRestart6;
    }

    // returns set of formated files which we should not copy to the cwd - let them stay in the modpack directory
    private Set<String> getFilesNotToCopy(Set<Jsons.ModpackContentFields.ModpackContentItem> modpackContentItems, Set<String> workaroundMods) {
        Set<String> filesNotToCopy = new HashSet<>();

        // Make list of files which we do not copy to the running directory
        for (Jsons.ModpackContentFields.ModpackContentItem item : modpackContentItems) {
            if (item.forceCopy) {
                continue;
            }

            // We only want to copy editable file if its downloaded first time
            // So we add to ignored any other editable file
            if (item.editable && !newDownloadedFiles.contains(item.file)) {
                filesNotToCopy.add(item.file);
            }

            // We only want to copy mods which need a workaround
            if (item.type.equals("mod") && !workaroundMods.contains(item.file)) {
                filesNotToCopy.add(item.file);
            }
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
            if (Files.isDirectory(path) || path.equals(modpackContentFile)) {
                continue;
            }

            String formattedFile = SmartFileUtils.formatPath(path, modpackDir);
            if (modpackFiles.contains(formattedFile)) {
                continue;
            }

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
        if (directory == null || !SmartFileUtils.isEmptyDirectory(directory)) {
            return;
        }

        LOGGER.info("Deleting empty directory {}", directory);
        SmartFileUtils.executeOrder66(directory);
        deleteEmptyParentDirectoriesRecursively(directory.getParent());
    }
}
