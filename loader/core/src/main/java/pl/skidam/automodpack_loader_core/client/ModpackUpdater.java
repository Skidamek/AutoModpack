package pl.skidam.automodpack_loader_core.client;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.MmcPackMagic;
import pl.skidam.automodpack_core.utils.WorkaroundUtil;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

public class ModpackUpdater {
    public Changelogs changelogs = new Changelogs();
    public DownloadManager downloadManager;
    public FetchManager fetchManager;
    public long totalBytesToDownload = 0;
    public boolean fullDownload = false;
    private Jsons.ModpackContentFields serverModpackContent;
    private String unModifiedSMC;
    private WorkaroundUtil workaroundUtil;
    public Map<Jsons.ModpackContentFields.ModpackContentItem, DownloadManager.Urls> failedDownloads = new HashMap<>();
    private final Set<String> newDownloadedFiles = new HashSet<>(); // Only files which did not exist before. Because some files may have the same name/path and be updated.

    private String modpackLink;
    private Path modpackDir;
    private Path modpackContentFile;


    public String getModpackName() {
        return serverModpackContent.modpackName;
    }

    public void prepareUpdate(Jsons.ModpackContentFields modpackContent, String link, Path modpackPath) {
        serverModpackContent = modpackContent;
        modpackLink = link;
        modpackDir = modpackPath;

        if (modpackLink == null || modpackLink.isEmpty() || modpackPath.toString().isEmpty()) {
            throw new IllegalArgumentException("Link or modpackPath is null or empty");
        }

        try {
            modpackContentFile = modpackDir.resolve(hostModpackContentFile.getFileName());
            workaroundUtil = new WorkaroundUtil(modpackDir);

            // Handle the case where serverModpackContent is null
            if (serverModpackContent == null) {
                handleOfflineMode();
                return;
            }

            // Prepare for modpack update
            this.unModifiedSMC = GSON.toJson(serverModpackContent);

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

    private void handleOfflineMode() throws Exception {
        if (!Files.exists(modpackContentFile)) {
            return;
        }

        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);
        if (modpackContent == null) {
            return;
        }

        LOGGER.warn("Server is down, or you don't have access to internet, but we still want to load selected modpack");
        CheckAndLoadModpack();
    }


    public void CheckAndLoadModpack() throws Exception {

        boolean requiresRestart = applyModpack();

        if (requiresRestart) {
            LOGGER.info("Modpack is not loaded");
            UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
            new ReLauncher(modpackDir, updateType, changelogs).restart(true);
        }

        // Load the modpack excluding mods from standard mods directory
        if (preload) {
            List<Path> modpackMods = Files.list(modpackDir.resolve("mods"))
                .filter(mod -> {
                    try {
                        String modHash = CustomFileUtils.getHash(mod, "SHA-1").orElse(null);

                        // if its in standard mods directory, we dont want to load it again
                        boolean isUnique = Files.list(MODS_DIR)
                                .map(path -> CustomFileUtils.getHash(path, "SHA-1").orElse(null))
                                .filter(Objects::nonNull)
                                .noneMatch(hash -> hash.equals(modHash));

                        boolean endsWithJar = mod.toString().endsWith(".jar");
                        boolean isFile = mod.toFile().isFile();

                        return isUnique && endsWithJar && isFile;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).toList();

            MODPACK_LOADER.loadModpack(modpackMods);
            return;
        }

        LOGGER.info("Modpack is already loaded");
    }

    // TODO split it into different methods, its too long
    public void startUpdate() {

        new ScreenManager().download(downloadManager, getModpackName());
        long start = System.currentTimeMillis();

        try {
            // Rename modpack
            modpackDir = ModpackUtils.renameModpackDir(serverModpackContent, modpackDir);
            modpackContentFile = modpackDir.resolve(modpackContentFile.getFileName());

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

                if (Objects.equals(serverSHA1, CustomFileUtils.getHash(path, "sha1").orElse(null))) {
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
            LOGGER.info("In queue left {} files to download ({}kb)", wholeQueue, totalBytesToDownload / 1024);

            downloadManager = new DownloadManager(totalBytesToDownload);
            new ScreenManager().download(downloadManager, getModpackName());

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

                    DownloadManager.Urls urls = new DownloadManager.Urls();

                    urls.addUrl(new DownloadManager.Url().getUrl(modpackLink + serverSHA1));

                    if (fetchManager.getFetchDatas().containsKey(item.sha1)) {
                        urls.addAllUrls(new DownloadManager.Url().getUrls(fetchManager.getFetchDatas().get(item.sha1).fetchedData().urls()));
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
                downloadManager.cancelAllAndShutdown();

                LOGGER.info("Finished downloading files in {}ms", System.currentTimeMillis() - startFetching);
            }

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
                // make json from the hashes list
                String hashesJson = GSON.toJson(hashesToRefresh.values());
                // send it to the server and get the new modpack content
                // TODO set client to a waiting for the server to respond screen
                LOGGER.warn("Trying to refresh the modpack content");
                LOGGER.info("Sending hashes to refresh: {}", hashesJson);
                var refreshedContentOptional = ModpackUtils.refreshServerModpackContent(modpackLink, hashesJson);
                if (refreshedContentOptional.isEmpty()) {
                    LOGGER.error("Failed to refresh the modpack content");
                } else {
                    LOGGER.info("Successfully refreshed the modpack content");
                    // retry the download
                    // success
                    // or fail and then show the error

                    downloadManager = new DownloadManager(totalBytesToDownload);
                    new ScreenManager().download(downloadManager, getModpackName());

                    var refreshedContent = refreshedContentOptional.get();
                    this.unModifiedSMC = GSON.toJson(refreshedContent);

                    // filter list to only the failed downloads
                    var refreshedFilteredList = refreshedContent.list.stream().filter(item -> hashesToRefresh.containsKey(item.file)).toList();

                    // TODO try to fetch again from modrinth and curseforge

                    var randomizedList = new LinkedList<>(refreshedFilteredList);
                    Collections.shuffle(randomizedList);
                    for (var item : randomizedList) {
                        String fileName = item.file;
                        String serverSHA1 = item.sha1;

                        Path downloadFile = CustomFileUtils.getPath(modpackDir, fileName);
                        DownloadManager.Urls urls = new DownloadManager.Urls();
                        urls.addUrl(new DownloadManager.Url().getUrl(modpackLink + serverSHA1));

                        LOGGER.info("Retrying to download {} from {}", fileName, urls);

                        Runnable failureCallback = () -> {
                            failedDownloads.put(item, urls);
                        };

                        Runnable successCallback = () -> {
                            changelogs.changesAddedList.put(downloadFile.getFileName().toString(), null);
                        };

                        downloadManager.download(downloadFile, serverSHA1, urls, successCallback, failureCallback);
                    }

                    downloadManager.joinAll();
                    downloadManager.cancelAllAndShutdown();

                    LOGGER.info("Finished refreshed downloading files in {}ms", System.currentTimeMillis() - startFetching);
                }
            }

            LOGGER.info("Done, saving {}", modpackContentFile.getFileName().toString());

            // Downloads completed
            Files.write(modpackContentFile, unModifiedSMC.getBytes());

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
                        LOGGER.error("Failed to download: " + item.file + " from " + urls);
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
            LOGGER.error("Modpack host of " + modpackLink + " is not responding", e);
        } catch (InterruptedException e) {
            LOGGER.info("Interrupted the download");
        } catch (Exception e) {
            new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
            e.printStackTrace();
        }
    }

    // returns true if restart is required
    private boolean applyModpack() throws Exception {
        ModpackUtils.selectModpack(modpackDir, modpackLink, newDownloadedFiles);
        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

        if (modpackContent == null) {
            LOGGER.error("Modpack content is null");
            return false;
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

        // Make a list of ignored files to ignore them while copying
        boolean needsRestart0 = deleteNonModpackFiles(modpackContent);
        Set<String> workaroundMods =  workaroundUtil.getWorkaroundMods(modpackContent);
        Set<String> filesNotToCopy = getIgnoredFiles(modpackContent.list, workaroundMods);

        // Copy files to running directory
        boolean needsRestart1 = ModpackUtils.correctFilesLocations(modpackDir, modpackContent, filesNotToCopy);

        // Prepare modpack, analyze nested mods, copy necessary nested mods over to standard mods directory
        List<LoaderManagerService.Mod> conflictingNestedMods = MODPACK_LOADER.getModpackNestedConflicts(modpackDir, filesNotToCopy);

        if (!conflictingNestedMods.isEmpty()) {
            LOGGER.warn("Found conflicting nested mods: {}", conflictingNestedMods);
        }

        boolean needsRestart2 = ModpackUtils.fixNestedMods(conflictingNestedMods);
        Set<String> newIgnoredFiles = ModpackUtils.getIgnoredWithNested(conflictingNestedMods, filesNotToCopy);

        // Remove duplicate mods
        var dupeMods = ModpackUtils.getDupeMods(modpackDir, newIgnoredFiles);
        workaroundMods = workaroundUtil.getWorkaroundMods(modpackContent);

        boolean needsRestart3 = ModpackUtils.removeDupeMods(dupeMods, workaroundMods);

        return needsRestart0 || needsRestart1 || needsRestart2 || needsRestart3;
    }

    // returns set of formated files which we should not copy to the cwd - let them stay in the modpack directory
    private Set<String> getIgnoredFiles(Set<Jsons. ModpackContentFields. ModpackContentItem> modpackContentItems, Set<String> workaroundMods) {
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
        List<Path> pathList = Files.walk(modpackDir).toList();
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
            if ((Files.exists(runPath) && CustomFileUtils.compareFileHashes(path, runPath, "SHA-1")) && (!formattedFile.startsWith("/mods/") || workaroundMods.contains(formattedFile))) {
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