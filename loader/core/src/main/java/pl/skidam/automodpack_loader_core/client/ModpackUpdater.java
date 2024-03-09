package pl.skidam.automodpack_loader_core.client;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.MmcPackMagic;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.mods.SetupMods;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.*;

import java.io.File;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;
import static pl.skidam.automodpack_core.utils.CustomFileUtils.mapAllFiles;

public class ModpackUpdater {
    public Changelogs changelogs = new Changelogs();
    public static DownloadManager downloadManager;
    public static FetchManager fetchManager;
    public static long totalBytesToDownload = 0;
    public static boolean fullDownload = false;
    private static Jsons.ModpackContentFields serverModpackContent;
    private static String unModifiedSMC;
    public Map<Jsons.ModpackContentFields.ModpackContentItem, DownloadManager.Urls> failedDownloads = new HashMap<>();
    public static String getModpackName() {
        return serverModpackContent.modpackName;
    }

    public void startModpackUpdate(Jsons.ModpackContentFields serverModpackContent, String link, Path modpackDir) {
        if (link == null || link.isEmpty() || modpackDir.toString().isEmpty()) {
            throw new IllegalArgumentException("Link or modpackDir is null or empty");
        }

        try {
            Path modpackContentFile = modpackDir.resolve(hostModpackContentFile.getFileName());
            Path workingDirectory = Path.of("./");

            if (serverModpackContent == null)  { // the server is down, or you don't have access to internet, but we still want to load selected modpack

                if (!Files.exists(modpackContentFile)) {
                    return;
                }

                Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);
                if (modpackContent == null) {
                    return;
                }

                LOGGER.warn("Server is down, or you don't have access to internet, but we still want to load selected modpack");

                CheckAndLoadModpack(modpackDir, modpackContentFile, workingDirectory);
                return;
            }

            serverModpackContent.link = link;
            ModpackUpdater.serverModpackContent = serverModpackContent;
            ModpackUpdater.unModifiedSMC = GSON.toJson(serverModpackContent);

            if (!Files.exists(modpackDir)) {
                Files.createDirectories(modpackDir);
            }

            if (Files.exists(modpackContentFile)) {

                // Rename modpack folder to modpack name if exists
                List<Path> modpackFiles = ModpackUtils.renameModpackDir(modpackContentFile, serverModpackContent, modpackDir);
                if (modpackFiles != null) {
                    modpackDir = modpackFiles.get(0);
                    modpackContentFile = modpackFiles.get(1);
                }

                if (!ModpackUtils.isUpdate(serverModpackContent, modpackDir)) {
                    // check if modpack is loaded now loaded

                    LOGGER.info("Modpack is up to date");

                    CheckAndLoadModpack(modpackDir, modpackContentFile, workingDirectory);
                    return;
                }
            } else if (!preload && new ScreenManager().getScreen().isPresent()) {
                fullDownload = true;
                new ScreenManager().danger(new ScreenManager().getScreen().get(), link, modpackDir, modpackContentFile);
                return;
            }

            LOGGER.warn("Modpack update found");

            new ScreenManager().download(downloadManager, ModpackUpdater.getModpackName());

            ModpackUpdaterMain(link, modpackDir, modpackContentFile);

        } catch (Exception e) {
            LOGGER.error("Error while initializing modpack updater");
            e.printStackTrace();
        }
    }

    public void CheckAndLoadModpack(Path modpackDir, Path modpackContentFile, Path workingDirectory) throws Exception {

        if (preload) {
            finishModpackUpdate(modpackDir, modpackContentFile);
            new SetupMods().loadModpack(modpackDir);
            return;
        }

        List<Path> filesBefore  = mapAllFiles(workingDirectory, new ArrayList<>());

        finishModpackUpdate(modpackDir, modpackContentFile);

        List<Path> filesAfter = mapAllFiles(workingDirectory, new ArrayList<>());

        List<Path> addedFiles = new ArrayList<>();
        List<Path> deletedFiles = new ArrayList<>();

        for (Path file : filesAfter) {
            if (!filesBefore.contains(file)) {
                addedFiles.add(file);
            }
        }

        for (Path file : filesBefore) {
            if (!filesAfter.contains(file)) {
                deletedFiles.add(file);
            }
        }

        if (filesAfter.equals(filesBefore) || (addedFiles.isEmpty() && deletedFiles.isEmpty())) {
            LOGGER.info("Modpack is already loaded");
            return;
        } else {
            LOGGER.info("Modpack is not loaded");
        }

        LOGGER.info("Added files: {}", addedFiles);
        LOGGER.info("Deleted files: {}", deletedFiles);

        UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;

        new ReLauncher.Restart(modpackDir, updateType, changelogs);
    }

    // TODO rewrite it, split it into many different methods, its a mess
    public void ModpackUpdaterMain(String link, Path modpackDir, Path modpackContentFile) {

        long start = System.currentTimeMillis();

        try {
            // Rename modpack
            List<Path> modpackFiles = ModpackUtils.renameModpackDir(modpackContentFile, serverModpackContent, modpackDir);
            if (modpackFiles != null) {
                modpackDir = modpackFiles.get(0);
                modpackContentFile = modpackFiles.get(1);
            }

            Iterator<Jsons.ModpackContentFields.ModpackContentItem> iterator = serverModpackContent.list.iterator();

            // FETCH

            while (iterator.hasNext()) {
                Jsons.ModpackContentFields.ModpackContentItem modpackContentField = iterator.next();
                String file = modpackContentField.file;
                String serverSHA1 = modpackContentField.sha1;

                Path path = Paths.get(modpackDir + File.separator + file);

                if (Files.exists(path) && modpackContentField.editable) {
                    LOGGER.info("Skipping editable file: {}", file);
                    iterator.remove();
                    continue;
                }

                if (!Files.exists(path)) {
                    path = Paths.get("./" + file);
                }

                if (!Files.exists(path)) {
                    continue;
                }

                if (Objects.equals(serverSHA1, CustomFileUtils.getHash(path, "sha1").orElse(null))) {
                    LOGGER.info("Skipping already downloaded file: {}", file);
                    iterator.remove();
                }
            }

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

            int wholeQueue = serverModpackContent.list.size();
            LOGGER.info("In queue left {} files to download ({}kb)", wholeQueue, totalBytesToDownload / 1024);

            downloadManager = new DownloadManager(totalBytesToDownload);
            new ScreenManager().download(downloadManager, ModpackUpdater.getModpackName());

            if (wholeQueue > 0) {

                for (var item : serverModpackContent.list) {

                    String fileName = item.file;
                    String serverSHA1 = item.sha1;

                    Path downloadFile = Paths.get(modpackDir + File.separator + fileName);
                    DownloadManager.Urls urls = new DownloadManager.Urls();

                    if (fetchManager.getFetchDatas().containsKey(item.sha1)) {
                        urls.addAllUrls(new DownloadManager.Url().getUrls(fetchManager.getFetchDatas().get(item.sha1).fetchedData().urls()));
                    }

                    urls.addUrl(new DownloadManager.Url().getUrl(link + serverSHA1));

                    Runnable failureCallback = () -> {
                        LOGGER.error("Failed to download {} from {}", fileName, urls);
                        failedDownloads.put(item, urls);
                    };

                    Runnable successCallback = () -> {
                        LOGGER.info("Successfully downloaded {} from {}", fileName, urls);

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
                var refreshedContentOptional = ModpackUtils.refreshServerModpackContent(link, hashesJson);
                if (refreshedContentOptional.isEmpty()) {
                    LOGGER.error("Failed to refresh the modpack content");
                } else {
                    LOGGER.info("Successfully refreshed the modpack content");
                    // retry the download
                    // success
                    // or fail and then show the error

                    downloadManager = new DownloadManager(totalBytesToDownload);
                    new ScreenManager().download(downloadManager, ModpackUpdater.getModpackName());

                    var refreshedContent = refreshedContentOptional.get();
                    refreshedContent.link = link;
                    ModpackUpdater.unModifiedSMC = GSON.toJson(refreshedContent);

                    // filter list to only the failed downloads
                    var refreshedFilteredList = refreshedContent.list.stream().filter(item -> hashesToRefresh.containsKey(item.file)).toList();

                    // TODO try to fetch again from modrinth and curseforge

                    for (var item : refreshedFilteredList) {
                        String fileName = item.file;
                        String serverSHA1 = item.sha1;

                        Path downloadFile = Paths.get(modpackDir + File.separator + fileName);
                        DownloadManager.Urls urls = new DownloadManager.Urls();
                        urls.addUrl(new DownloadManager.Url().getUrl(link + serverSHA1));

                        LOGGER.info("Retrying to download {} from {}", fileName, urls);

                        Runnable failureCallback = () -> {
                            LOGGER.error("Failed to download {} from {}", fileName, urls);
                            failedDownloads.put(item, urls);
                        };

                        Runnable successCallback = () -> {
                            LOGGER.info("Successfully downloaded {} from {}", fileName, urls);
                            changelogs.changesAddedList.put(downloadFile.getFileName().toString(), null);
                        };


                        downloadManager.download(downloadFile, serverSHA1, urls, successCallback, failureCallback);
                    }

                    downloadManager.joinAll();
                    downloadManager.cancelAllAndShutdown();

                    LOGGER.info("Finished refreshed downloading files in {}ms", System.currentTimeMillis() - startFetching);
                }
            }


            LOGGER.info("Done, saving modpack-content.json");

            // Downloads completed
            Files.write(modpackContentFile, unModifiedSMC.getBytes());

            Path cwd = Path.of(System.getProperty("user.dir"));
            CustomFileUtils.deleteDummyFiles(cwd, serverModpackContent.list);

            String modpackName = modpackDir.getFileName().toString();
            ModpackUtils.addModpackToList(modpackName);


            if (preload) {
                LOGGER.info("Update completed! Took: {}ms", System.currentTimeMillis() - start);
                CheckAndLoadModpack(modpackDir, modpackContentFile, cwd);
            } else {
                finishModpackUpdate(modpackDir, modpackContentFile);
                if (!failedDownloads.isEmpty()) {
                    StringBuilder failedFiles = new StringBuilder();
                    for (var download : failedDownloads.entrySet()) {
                        var item = download.getKey();
                        var urls = download.getValue();
                        LOGGER.error("Failed to download: " + item.file + " from " + urls);
                        failedFiles.append(item.file);
                    }

                    new ScreenManager().error("automodpack.error.files", "Failed to download: " + failedFiles, "automodpack.error.logs");;

                    LOGGER.warn("Update *completed* with ERRORS! Took: {}ms", System.currentTimeMillis() - start);

                    if (preload) {
                        new ReLauncher.Restart("Failed to complete update without errors!");
                    }

                    return;
                }

                LOGGER.info("Update completed! Took: {}ms", System.currentTimeMillis() - start);

                UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;
                new ReLauncher.Restart(modpackDir, updateType, changelogs);
            }
        } catch (SocketTimeoutException | ConnectException e) {
            LOGGER.error("Modpack host of " + link + " is not responding", e);
        } catch (InterruptedException e) {
            LOGGER.info("Interrupted the download");
        } catch (Exception e) {
            new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
            e.printStackTrace();
        }
    }

    private void finishModpackUpdate(Path modpackDir, Path modpackContentFile) throws Exception {
        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

        if (modpackContent == null) {
            LOGGER.error("Modpack content is null");
            return;
        }

        if (serverModpackContent != null) {

            // Change loader and minecraft version in launchers like prism, multimc.
            if (serverModpackContent.loader != null && serverModpackContent.loaderVersion != null) {
                if (serverModpackContent.loader.equals(LOADER)) { // Server may use different loader than client
                    MmcPackMagic.changeVersion(MmcPackMagic.modLoaderUIDs, serverModpackContent.loaderVersion); // Update loader version
                }
            }

            if (serverModpackContent.mcVersion != null) {
                MmcPackMagic.changeVersion(MmcPackMagic.mcVerUIDs, serverModpackContent.mcVersion); // Update minecraft version
            }
        }

        // make a list of editable files to ignore them while copying
        List<String> editableFiles = new ArrayList<>();
        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : modpackContent.list) {

            String fileName = Paths.get(modpackContentField.file).getFileName().toString();

            // Don't add to editable if just downloaded
            if (changelogs.changesAddedList.containsKey(fileName)) {
                continue;
            }

            if (modpackContentField.editable) {
                editableFiles.add(modpackContentField.file);
            }
        }

        // copy files to running directory
        // map running dir files
        ModpackUtils.correctFilesLocations(modpackDir, modpackContent, editableFiles);

        List<String> modpackFileNames = new ArrayList<>();
        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : modpackContent.list) {
            String fileName = Path.of(modpackContentField.file).getFileName().toString();
            modpackFileNames.add(fileName);
        }

        List<Path> pathList = Files.walk(modpackDir).toList();
        for (Path path : pathList) {

            if (Files.isDirectory(path)) {
                continue;
            }
            if (path.equals(modpackContentFile)) {
                continue;
            }

            String pathName = path.getFileName().toString();

            if (modpackFileNames.contains(pathName)) { // FIXME: because we are checking just the name of the file, there's no guarantee that we delete or not, the correct file.
                continue;
            }

            Path runPath = Path.of("." + path.toString().replace(modpackDir.toString(), ""));

            if (Files.exists(runPath) && CustomFileUtils.compareFileHashes(path, runPath, "SHA-1")) {
                LOGGER.info("Deleting {} and {}", path, runPath);
                CustomFileUtils.forceDelete(runPath);
            } else {
                LOGGER.info("Deleting {}", path);
            }

            CustomFileUtils.forceDelete(path);
            changelogs.changesDeletedList.put(pathName, null);
        }
    }
}