package pl.skidam.automodpack_loader_core.client;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

// TODO: clean up this mess
public class ModpackUpdater {
    public Changelogs changelogs = new Changelogs();
    public DownloadManager downloadManager;
    public FetchManager fetchManager;
    public long totalBytesToDownload = 0;
    public boolean fullDownload = false;
    private Jsons.ModpackContentFields serverModpackContent;
    private String modpackContentJson;
    private WorkaroundUtil workaroundUtil;
    public Map<Jsons.ModpackContentFields.ModpackContentItem, List<String>> failedDownloads = new HashMap<>();
    private final Set<String> newDownloadedFiles = new HashSet<>(); // Only files which did not exist before. Because some files may have the same name/path and be updated.
    private Jsons.ModpackAddresses modpackAddresses;
    private Secrets.Secret modpackSecret;
    private Path modpackDir;
    private Path modpackContentFile;


    public String getModpackName() {
        return serverModpackContent.modpackName;
    }

    public void prepareUpdate(Jsons.ModpackContentFields modpackContent, Jsons.ModpackAddresses modpackAddresses, Secrets.Secret secret, Path modpackPath) {
        this.serverModpackContent = modpackContent;
        this.modpackAddresses = modpackAddresses;
        this.modpackSecret = secret;
        this.modpackDir = modpackPath;

        if (this.modpackAddresses.isAnyEmpty() || modpackPath.toString().isEmpty()) {
            throw new IllegalArgumentException("Address or modpackPath is null or empty");
        }

        try {
            modpackContentFile = modpackDir.resolve(hostModpackContentFile.getFileName());
            workaroundUtil = new WorkaroundUtil(modpackDir);

            // Handle the case where serverModpackContent is null
            if (serverModpackContent == null) {
                CheckAndLoadModpack();
                return;
            }

            // Prepare for modpack update
            modpackContentJson = GSON.toJson(serverModpackContent);

            // Create directories if they don't exist
            if (!Files.exists(modpackDir)) {
                Files.createDirectories(modpackDir);
            }

            // Handle existing modpack content file
            if (Files.exists(modpackContentFile)) {
                modpackDir = ModpackUtils.renameModpackDir(serverModpackContent, modpackDir);
                modpackContentFile = modpackDir.resolve(modpackContentFile.getFileName());

                // Check if an update is needed
                if (!ModpackUtils.isUpdate(serverModpackContent, modpackDir)) {
                    LOGGER.info("Modpack is up to date");
                    Files.writeString(modpackContentFile, modpackContentJson);
                    CheckAndLoadModpack();
                    return;
                }
            } else if (!preload) {
                fullDownload = true;
                new ScreenManager().danger(new ScreenManager().getScreen().orElseThrow(), this);
                return;
            }

            LOGGER.warn("Modpack update found");
            startUpdate();
        } catch (Exception e) {
            LOGGER.error("Error while initializing modpack updater", e);
        }
    }

    public void CheckAndLoadModpack() throws Exception {
        if (!Files.exists(modpackDir))
            return;

        boolean requiresRestart = applyModpack();

        if (requiresRestart) {
            LOGGER.info("Modpack is not loaded");
            UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
            new ReLauncher(modpackDir, updateType, changelogs).restart(true);
            return;
        }

        // Load the modpack excluding mods from standard mods directory without need to restart the game
        if (preload) {
            List<String> standardModsHashes;
            List<Path> modpackMods = List.of();

            try (Stream<Path> standardModsStream = Files.list(MODS_DIR)) {
                standardModsHashes = standardModsStream
                        .map(CustomFileUtils::getHash)
                        .filter(Objects::nonNull)
                        .toList();
            }

            Path modpackModsDir = modpackDir.resolve("mods");
            if (Files.exists(modpackModsDir)) {
                try (Stream<Path> modpackModsStream = Files.list(modpackModsDir)) {
                    modpackMods = modpackModsStream
                            .filter(mod -> {
                                String modHash = CustomFileUtils.getHash(mod);

                                // if its in standard mods directory, we dont want to load it again
                                boolean isUnique = standardModsHashes.stream().noneMatch(hash -> hash.equals(modHash));
                                boolean endsWithJar = mod.toString().endsWith(".jar");
                                boolean isFile = mod.toFile().isFile();

                                return isUnique && endsWithJar && isFile;
                            }).toList();
                }
            }

            MODPACK_LOADER.loadModpack(modpackMods);
            return;
        }

        LOGGER.info("Modpack is already loaded");
    }

    // TODO split it into different methods, its too long
    public void startUpdate() {

        if (modpackSecret == null) {
            LOGGER.error("Cannot update modpack, secret is null");
            return;
        }

        new ScreenManager().download(downloadManager, getModpackName());
        long start = System.currentTimeMillis();

        try {
            // Rename modpack
            modpackDir = ModpackUtils.renameModpackDir(serverModpackContent, modpackDir);
            modpackContentFile = modpackDir.resolve(modpackContentFile.getFileName());
            workaroundUtil = new WorkaroundUtil(modpackDir);

            Iterator<Jsons.ModpackContentFields.ModpackContentItem> iterator = serverModpackContent.list.iterator();

            // CLEAN UP THE LIST

            int skippedDownloadedFiles = 0;
            int skippedEditableFiles = 0;

            while (iterator.hasNext()) {
                Jsons.ModpackContentFields.ModpackContentItem modpackContentField = iterator.next();
                String file = modpackContentField.file;
                String serverSHA1 = modpackContentField.sha1;

                Path path = CustomFileUtils.getPath(modpackDir, file);

                if (Files.exists(path) && modpackContentField.editable) {
                    skippedEditableFiles++;
                    LOGGER.info("Skipping editable file: {}", file);
                    iterator.remove();
                    continue;
                }

                if (!Files.exists(path)) {
                    path = CustomFileUtils.getPathFromCWD(file);
                }

                if (!Files.exists(path)) {
                    continue;
                }

                if (Objects.equals(serverSHA1, CustomFileUtils.getHash(path))) {
                    skippedDownloadedFiles++;
                    iterator.remove();
                }
            }

            if (skippedEditableFiles > 0) {
                LOGGER.info("Skipped {} editable files", skippedEditableFiles);
            }

            if (skippedDownloadedFiles > 0) {
                LOGGER.info("Skipped {} already downloaded files", skippedDownloadedFiles);
            }

            // FETCH

            long startFetching = System.currentTimeMillis();

            List<FetchManager.FetchData> fetchDatas = new LinkedList<>();

            for (Jsons.ModpackContentFields.ModpackContentItem field : serverModpackContent.list) {

                totalBytesToDownload += Long.parseLong(field.size);

                String fileType = field.type;

                // Check if the file is mod, shaderpack or resourcepack is available to download from modrinth or curseforge
                if (fileType.equals("mod") || fileType.equals("shader") || fileType.equals("resourcepack")) {
                    fetchDatas.add(new FetchManager.FetchData(field.file, field.sha1, field.murmur, field.size, fileType));
                }
            }

            fetchManager = new FetchManager(fetchDatas);
            new ScreenManager().fetch(fetchManager);
            fetchManager.fetch();
            LOGGER.info("Finished fetching urls in {}ms", System.currentTimeMillis() - startFetching);


            // DOWNLOAD

            newDownloadedFiles.clear();
            int wholeQueue = serverModpackContent.list.size();
            LOGGER.info("In queue left {} files to download ({}MB)", wholeQueue, totalBytesToDownload / 1024 / 1024);

            DownloadClient downloadClient = DownloadClient.tryCreate(modpackAddresses, modpackSecret.secretBytes(),
                    Math.min(wholeQueue, 5), ModpackUtils.userValidationCallback(modpackAddresses.hostAddress, false));
            if (downloadClient == null) {
                return;
            }

            downloadManager = new DownloadManager(totalBytesToDownload);
            new ScreenManager().download(downloadManager, getModpackName());
            downloadManager.attachDownloadClient(downloadClient);

            if (wholeQueue > 0) {
                var randomizedList = new LinkedList<>(serverModpackContent.list);
                Collections.shuffle(randomizedList);
                for (var item : randomizedList) {

                    String fileName = item.file;
                    String serverSHA1 = item.sha1;

                    Path downloadFile = CustomFileUtils.getPath(modpackDir, fileName);

                    if (!Files.exists(downloadFile)) {
                        newDownloadedFiles.add(fileName);
                    }

                    List<String> urls = new ArrayList<>();
                    if (fetchManager.getFetchDatas().containsKey(item.sha1)) {
                        urls.addAll(fetchManager.getFetchDatas().get(item.sha1).fetchedData().urls());
                    }

                    Runnable failureCallback = () -> {
                        failedDownloads.put(item, urls);
                    };

                    Runnable successCallback = () -> {
                        List<String> mainPageUrls = new LinkedList<>();
                        if (fetchManager != null && fetchManager.getFetchDatas().get(item.sha1) != null) {
                            mainPageUrls = fetchManager.getFetchDatas().get(item.sha1).fetchedData().mainPageUrls();
                        }

                        changelogs.changesAddedList.put(downloadFile.getFileName().toString(), mainPageUrls);
                    };


                    downloadManager.download(downloadFile, serverSHA1, urls, successCallback, failureCallback);
                }

                downloadManager.joinAll();

                LOGGER.info("Finished downloading files in {}ms", System.currentTimeMillis() - startFetching);
            }

            if (downloadManager.isCanceled()) {
                LOGGER.warn("Download canceled");
                return;
            }

            downloadManager.cancelAllAndShutdown();
            totalBytesToDownload = 0;

            Map<String, String> hashesToRefresh = new HashMap<>(); // File name, hash
            var failedDownloadsSecMap = new HashMap<>(failedDownloads);
            failedDownloadsSecMap.forEach((k, v) -> {
                hashesToRefresh.put(k.file, k.sha1);
                failedDownloads.remove(k);
                totalBytesToDownload += Long.parseLong(k.size);
            });

            LOGGER.warn("Failed to download {} files", hashesToRefresh.size());

            if (!hashesToRefresh.isEmpty()) {
                // make byte[][] from hashesToRefresh.values()
                byte[][] hashesArray = hashesToRefresh.values().stream()
                        .map(String::getBytes)
                        .toArray(byte[][]::new);

                // send it to the server and get the new modpack content
                // TODO set client to a waiting for the server to respond screen
                LOGGER.warn("Trying to refresh the modpack content");
                LOGGER.info("Sending hashes to refresh: {}", hashesToRefresh.values());
                var refreshedContentOptional = ModpackUtils.refreshServerModpackContent(modpackAddresses, modpackSecret, hashesArray, false);
                if (refreshedContentOptional.isEmpty()) {
                    LOGGER.error("Failed to refresh the modpack content");
                } else {
                    LOGGER.info("Successfully refreshed the modpack content");
                    // retry the download
                    // success
                    // or fail and then show the error

                    var refreshedContent = refreshedContentOptional.get();
                    this.modpackContentJson = GSON.toJson(refreshedContent);

                    // filter list to only the failed downloads
                    var refreshedFilteredList = refreshedContent.list.stream().filter(item -> hashesToRefresh.containsKey(item.file)).toList();

                    downloadClient = DownloadClient.tryCreate(modpackAddresses, modpackSecret.secretBytes(), Math.min(refreshedFilteredList.size(), 5), ModpackUtils.userValidationCallback(modpackAddresses.hostAddress, false));
                    if (downloadClient == null) {
                        return;
                    }
                    downloadManager = new DownloadManager(totalBytesToDownload);
                    new ScreenManager().download(downloadManager, getModpackName());
                    downloadManager.attachDownloadClient(downloadClient);

                    // TODO try to fetch again from modrinth and curseforge

                    var randomizedList = new LinkedList<>(refreshedFilteredList);
                    Collections.shuffle(randomizedList);
                    for (var item : randomizedList) {
                        String fileName = item.file;
                        String serverSHA1 = item.sha1;

                        Path downloadFile = CustomFileUtils.getPath(modpackDir, fileName);

                        LOGGER.info("Retrying to download {} from {}", fileName, modpackAddresses.hostAddress.getHostName());

                        Runnable failureCallback = () -> {
                            failedDownloads.put(item, List.of());
                        };

                        Runnable successCallback = () -> {
                            changelogs.changesAddedList.put(downloadFile.getFileName().toString(), null);
                        };

                        downloadManager.download(downloadFile, serverSHA1, List.of(), successCallback, failureCallback);
                    }

                    downloadManager.joinAll();

                    if (downloadManager.isCanceled()) {
                        LOGGER.warn("Download canceled");
                        return;
                    }

                    downloadManager.cancelAllAndShutdown();

                    LOGGER.info("Finished refreshed downloading files in {}ms", System.currentTimeMillis() - startFetching);
                }
            }

            LOGGER.info("Done, saving {}", modpackContentFile);

            // Downloads completed
            Files.writeString(modpackContentFile, modpackContentJson);

            Path cwd = Path.of(System.getProperty("user.dir"));
            CustomFileUtils.deleteDummyFiles(cwd, serverModpackContent.list);

            if (preload) {
                LOGGER.info("Update completed! Took: {}ms", System.currentTimeMillis() - start);
                CheckAndLoadModpack();
            } else {
                applyModpack();
                if (!failedDownloads.isEmpty()) {
                    StringBuilder failedFiles = new StringBuilder();
                    for (var download : failedDownloads.entrySet()) {
                        var item = download.getKey();
                        var urls = download.getValue();
                        LOGGER.error("{}{}", "Failed to download: " + item.file + " from ", urls);
                        failedFiles.append(item.file);
                    }

                    new ScreenManager().error("automodpack.error.files", "Failed to download: " + failedFiles, "automodpack.error.logs");

                    LOGGER.warn("Update *completed* with ERRORS! Took: {}ms", System.currentTimeMillis() - start);

                    return;
                }

                LOGGER.info("Update completed! Took: {}ms", System.currentTimeMillis() - start);

                UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
                new ReLauncher(modpackDir, updateType, changelogs).restart(false);
            }
        } catch (SocketTimeoutException | ConnectException e) {
            LOGGER.error("{} is not responding", "Modpack host of " + modpackAddresses.hostAddress, e);
        } catch (InterruptedException e) {
            LOGGER.info("Interrupted the download");
        } catch (Exception e) {
            new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
            e.printStackTrace();
        }
    }

    // returns true if restart is required
    private boolean applyModpack() throws Exception {
        ModpackUtils.selectModpack(modpackDir, modpackAddresses, newDownloadedFiles);
        try { // try catch this error there because we don't want to stop the whole method just because of that
            SecretsStore.saveClientSecret(clientConfig.selectedModpack, modpackSecret);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Failed to save client secret", e);
        }
        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

        if (modpackContent == null) {
            throw new IllegalStateException("Failed to load modpack content"); // Something gone very wrong...
        }

        if (serverModpackContent != null) {
            // Change loader and minecraft version in launchers like prism, multimc.
            if (serverModpackContent.loader != null && serverModpackContent.loaderVersion != null) {
                if (serverModpackContent.loader.equals(LOADER)) { // Server may use different loader than client
                    var UID = switch (LOADER) {
                        case "fabric" -> MmcPackMagic.FABRIC_LOADER_UID;
                        case "quilt" -> MmcPackMagic.QUILT_LOADER_UID;
                        case "forge" -> MmcPackMagic.FORGE_LOADER_UID;
                        case "neoforge" -> MmcPackMagic.NEOFORGE_LOADER_UID;
                        default -> null;
                    };
                    MmcPackMagic.changeVersion(UID, serverModpackContent.loaderVersion); // Update loader version
                }
            }

            if (serverModpackContent.mcVersion != null) {
                MmcPackMagic.changeVersion(MmcPackMagic.MINECRAFT_UID, serverModpackContent.mcVersion); // Update minecraft version
            }
        }

        boolean needsRestart0 = deleteNonModpackFiles(modpackContent);
        Set<String> workaroundMods = workaroundUtil.getWorkaroundMods(modpackContent);
        Set<String> filesNotToCopy = getIgnoredFiles(modpackContent.list, workaroundMods);

        // Copy files to running directory
        boolean needsRestart1 = ModpackUtils.correctFilesLocations(modpackDir, modpackContent, filesNotToCopy);

        // Prepare modpack, analyze nested mods, copy necessary nested mods over to standard mods directory
        List<FileInspection.Mod> conflictingNestedMods = MODPACK_LOADER.getModpackNestedConflicts(modpackDir);

        if (!conflictingNestedMods.isEmpty()) {
            LOGGER.warn("Found conflicting nested mods: {}", conflictingNestedMods);
        }

        final List<Path> modpackMods;
        final Collection<FileInspection.Mod> modpackModList;
        final List<Path> standardMods;
        final Collection<FileInspection.Mod> standardModList;

        Path modpackModsDir = modpackDir.resolve("mods");
        if (Files.exists(modpackModsDir)) {
            try (Stream<Path> modpackModsStream = Files.list(modpackModsDir)) {
                modpackMods = modpackModsStream.toList();
                modpackModList = modpackMods.stream().map(FileInspection::getMod).filter(Objects::nonNull).toList();
            }
        } else {
            modpackModList = List.of();
        }

        if (Files.exists(MODS_DIR)) {
            try (Stream<Path> standardModsStream = Files.list(MODS_DIR)) {
                standardMods = standardModsStream.toList();
                standardModList = new ArrayList<>(standardMods.stream().map(FileInspection::getMod).filter(Objects::nonNull).toList());
            }
        } else {
            standardModList = new ArrayList<>();
        }

        boolean needsRestart2 = ModpackUtils.fixNestedMods(conflictingNestedMods, standardModList);
        Set<String> ignoredFiles = ModpackUtils.getIgnoredWithNested(conflictingNestedMods, filesNotToCopy);

        // Remove duplicate mods
        boolean needsRestart3 = ModpackUtils.removeDupeMods(modpackDir, standardModList, modpackModList, ignoredFiles, workaroundMods);

        return needsRestart0 || needsRestart1 || needsRestart2 || needsRestart3;
    }

    // returns set of formated files which we should not copy to the cwd - let them stay in the modpack directory
    private Set<String> getIgnoredFiles(Set<Jsons.ModpackContentFields.ModpackContentItem> modpackContentItems, Set<String> workaroundMods) {
        Set<String> filesNotToCopy = new HashSet<>();

        // Make list of files which we do not copy to the running directory
        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentItem : modpackContentItems) {
            // We only want to copy editable file if its downloaded first time
            // So we add to ignored any other editable file
            if (modpackContentItem.editable && !newDownloadedFiles.contains(modpackContentItem.file)) {
                filesNotToCopy.add(modpackContentItem.file);
            }

            // We only want to copy mods which need a workaround
            if (modpackContentItem.type.equals("mod") && !workaroundMods.contains(modpackContentItem.file)) {
                filesNotToCopy.add(modpackContentItem.file);
            }
        }

        return filesNotToCopy;
    }

    // returns changed workaroundMods list
    private boolean deleteNonModpackFiles(Jsons.ModpackContentFields modpackContent) throws IOException {
        List<String> modpackFiles = modpackContent.list.stream().map(modpackContentField -> modpackContentField.file).toList();
        List<Path> pathList;
        try (Stream<Path> pathStream = Files.walk(modpackDir)) {
            pathList = pathStream.toList();
        }
        Set<String> workaroundMods = workaroundUtil.getWorkaroundMods(modpackContent);
        Set<Path> parentPaths = new HashSet<>();
        boolean needsRestart = false;

        for (Path path : pathList) {
            if (Files.isDirectory(path) || path.equals(modpackContentFile) || path.equals(workaroundUtil.getWorkaroundFile())) {
                continue;
            }

            String formattedFile = CustomFileUtils.formatPath(path, modpackDir);
            if (modpackFiles.contains(formattedFile)) {
                continue;
            }

            Path runPath = CustomFileUtils.getPathFromCWD(formattedFile);
            if ((Files.exists(runPath) && CustomFileUtils.hashCompare(path, runPath)) && (!formattedFile.startsWith("/mods/") || workaroundMods.contains(formattedFile))) {
                LOGGER.info("Deleting {} and {}", path, runPath);
                if (workaroundMods.contains(formattedFile)) { // We only delete workaround mods so only the mods that we have originally copied there
                    needsRestart = true;
                    workaroundMods.remove(formattedFile);
                }
                parentPaths.add(runPath.getParent());
                CustomFileUtils.forceDelete(runPath);
            } else {
                LOGGER.info("Deleting {}", path);
            }

            parentPaths.add(path.getParent());
            CustomFileUtils.forceDelete(path);
            changelogs.changesDeletedList.put(path.getFileName().toString(), null);
        }

        // recursively delete empty directories
        for (Path parentPath : parentPaths) {
            deleteEmptyParentDirectoriesRecursively(parentPath);
        }

        workaroundUtil.saveWorkaroundList(workaroundMods);

        return needsRestart;
    }

    private void deleteEmptyParentDirectoriesRecursively(Path directory) throws IOException {
        if (directory == null || !CustomFileUtils.isEmptyDirectory(directory)) {
            return;
        }

        LOGGER.info("Deleting empty directory {}", directory);
        CustomFileUtils.forceDelete(directory);
        deleteEmptyParentDirectoriesRecursively(directory.getParent());
    }
}
