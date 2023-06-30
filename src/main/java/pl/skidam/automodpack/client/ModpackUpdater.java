/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.client;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import pl.skidam.automodpack.Download;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.platforms.CurseForgeAPI;
import pl.skidam.automodpack.platforms.ModrinthAPI;
import pl.skidam.automodpack.utils.*;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static pl.skidam.automodpack.GlobalVariables.*;
import static pl.skidam.automodpack.config.ConfigTools.GSON;
import static pl.skidam.automodpack.utils.CustomFileUtils.mapAllFiles;
import static pl.skidam.automodpack.utils.RefactorStrings.getFormatedETA;

@SuppressWarnings("unchecked")
public class ModpackUpdater {
    public static List<DownloadInfo> downloadInfos = Collections.synchronizedList(new ArrayList<>());
    public static final int MAX_DOWNLOADS = 5; // at the same time
    public static final int MAX_FETCHES = 20; // at the same time
    public static boolean modrinthAPI = true;
    public static boolean curseforgeAPI = true;
    public static List<CompletableFuture<Void>> downloadFutures = new ArrayList<>();
    public static List<CompletableFuture<Void>> fetchFutures = new ArrayList<>();
    public static Map<String, Boolean> changelogList = new HashMap<>(); // <file, true - downloaded, false - deleted>
    private static ExecutorService DOWNLOAD_EXECUTOR;
    private static ExecutorService FETCH_EXECUTOR;
    public static int totalFetchedFiles = 0;
    public static long totalBytesDownloaded = 0;
    public static long totalBytesToDownload = 0;
    private static int alreadyDownloaded = 0;
    private static int wholeQueue = 0;
    public static boolean update;
    public static boolean fullDownload = false;
    private static Jsons.ModpackContentFields serverModpackContent;
    public static Map<String, String> failedDownloads = new HashMap<>(); // <file, url>
    private static byte[] serverModpackContentByteArray = new byte[0];

    public static String getStage() {
        return alreadyDownloaded + "/" + wholeQueue;
    }

    public static int getTotalPercentageOfFileSizeDownloaded() {
        return (int) ((double) totalBytesDownloaded / (double) totalBytesToDownload * 100);
    }

    public static double getTotalDownloadSpeed() {
        double totalSpeed = 0;
        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);
        for (DownloadInfo downloadInfo : downloadInfosCopy) {
            if (downloadInfo != null) {
                totalSpeed += downloadInfo.getDownloadSpeed();
            }
        }
        if (totalSpeed <= 0) {
            return 0;
        }
        return Math.round(totalSpeed * 10.0) / 10.0;
    }

    public static String getTotalETA() {
        double totalBytesPerSecond = 0;

        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);
        for (DownloadInfo downloadInfo : downloadInfosCopy) { // this is done like that to avoid ConcurrentModificationException
            if (downloadInfo == null) continue;
            totalBytesPerSecond += downloadInfo.getBytesPerSecond();
        }

        if (totalBytesPerSecond <= 0) return "N/A";

        double totalETA = (totalBytesToDownload - totalBytesDownloaded) / totalBytesPerSecond;

        if (totalETA < 0) return "N/A";

        return getFormatedETA(totalETA);
    }

    public static String getModpackName() {
        return serverModpackContent.modpackName;
    }

    public ModpackUpdater(Jsons.ModpackContentFields serverModpackContent, String link, Path modpackDir) {
        if (link == null || link.isEmpty() || modpackDir.toString().isEmpty()) return;

        try {
            Path modpackContentFile = Paths.get(modpackDir + File.separator + "modpack-content.json");
            if (serverModpackContent == null)  { // server is down, or you don't have access to internet, but we still want to load selected modpack

                LOGGER.warn("Server is down, or you don't have access to internet, but we still want to load selected modpack");

                if (!Files.exists(modpackContentFile)) {
                    return;
                }

                Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);
                if (modpackContent == null) return;

                List<Path> filesBefore = mapAllFiles(modpackDir, new ArrayList<>());

                finishModpackUpdate(modpackDir, modpackContentFile);

                List<Path> filesAfter = mapAllFiles(modpackDir, new ArrayList<>());

                if (filesAfter.equals(filesBefore)) {
                    LOGGER.info("Modpack is already loaded");
                    return;
                }

                // print out what files were added, deleted, updated
                List<Path> addedFiles = filesAfter.stream().filter(file -> !filesBefore.contains(file)).collect(Collectors.toList());
                List<Path> deletedFiles = filesBefore.stream().filter(file -> !filesAfter.contains(file)).collect(Collectors.toList());
                // print it
                LOGGER.info("Added files: " + addedFiles);
                LOGGER.info("Deleted files: " + deletedFiles);

                new ReLauncher.Restart(modpackDir, fullDownload);

                return;
            }

            serverModpackContent.link = link;
            ModpackUpdater.serverModpackContent = serverModpackContent;
            serverModpackContentByteArray = GSON.toJson(serverModpackContent).getBytes();

            if (!Files.exists(modpackDir)) {
                Files.createDirectories(modpackDir);
            }

            if (Files.exists(modpackContentFile)) {
                if (!ModpackUtils.isUpdate(serverModpackContent, modpackDir)) {
                    // check if modpack is loaded now loaded

                    LOGGER.info("Modpack is up to date");

                    List<Path> filesBefore = mapAllFiles(modpackDir, new ArrayList<>());

                    finishModpackUpdate(modpackDir, modpackContentFile);

                    List<Path> filesAfter = mapAllFiles(modpackDir, new ArrayList<>());

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

                    if (addedFiles.size() == 0 && deletedFiles.size() == 0) {
                        LOGGER.info("Modpack is already loaded");
                        return;
                    } else {
                        LOGGER.info("Modpack is not loaded");
                    }

                    // Print the results
                    LOGGER.info("Added files: " + addedFiles);
                    LOGGER.info("Deleted files: " + deletedFiles);

                    new ReLauncher.Restart(modpackDir, fullDownload);

                    return;
                }
            } else if (!preload && ScreenTools.getScreen() != null) {

                fullDownload = true;

                CompletableFuture.runAsync(() -> {
                    while (!ScreenTools.getScreenString().contains("dangerscreen")) {
                        ScreenTools.setTo.danger(ScreenTools.getScreen(), link, modpackDir, modpackContentFile);
                        new Wait(100);
                    }
                });
                return;
            }

            LOGGER.warn("Modpack update found");

            ModpackUpdaterMain(link, modpackDir, modpackContentFile);

        } catch (Exception e) {
            LOGGER.error("Error while initializing modpack updater");
            e.printStackTrace();
        }
    }

    public static void ModpackUpdaterMain(String link, Path modpackDir, Path modpackContentFile) {

        long start = System.currentTimeMillis();

        try {

            if (quest) {
                String modsPathString = modsPath.toString().substring(1) + "/";
                LOGGER.info("Quest mode is enabled, changing /mods/ path to {}", modsPathString);
                for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : serverModpackContent.list) {
                    if (modpackContentFile.toString().startsWith("/mods/")) {
                        modpackContentField.file = modpackContentField.file.replaceFirst("/mods/", modsPathString);
                    }
                }
            }

            Iterator<Jsons.ModpackContentFields.ModpackContentItem> iterator = serverModpackContent.list.iterator();

            while (iterator.hasNext()) {
                Jsons.ModpackContentFields.ModpackContentItem modpackContentField = iterator.next();
                String file = modpackContentField.file;
                String serverSHA1 = modpackContentField.sha1;

                Path path = Paths.get(modpackDir + File.separator + file);

                if (Files.exists(path) && modpackContentField.editable) {
                    LOGGER.info("Skipping editable file: " + file);
                    iterator.remove();
                    continue;
                }

                if (!Files.exists(path)) {
                    path = Paths.get("./" + file);
                }

                if (!Files.exists(path)) {
                    continue;
                }

                if (serverSHA1.equals(CustomFileUtils.getHash(path, "SHA-1"))) {
                    LOGGER.info("Skipping already downloaded file: " + file);
                    iterator.remove();
                }
            }

            long startTime = System.currentTimeMillis();


            if (APIsUp()) {
                ThreadFactory threadFactoryFetches = new ThreadFactoryBuilder()
                        .setNameFormat("AutoModpackFetch-%d")
                        .build();

                FETCH_EXECUTOR = Executors.newFixedThreadPool(
                        MAX_FETCHES,
                        threadFactoryFetches
                );

                totalFetchedFiles = 0;

                for (Jsons.ModpackContentFields.ModpackContentItem copyModpackContentField : serverModpackContent.list) {
                    while (fetchFutures.size() >= MAX_FETCHES) { // Async Setting - max `some` fetches at the same time
                        fetchFutures = fetchFutures.stream()
                                .filter(future -> !future.isDone())
                                .collect(Collectors.toList());
                    }

                    totalBytesToDownload += Long.parseLong(copyModpackContentField.size);

                    fetchFutures.add(fetchAsync(copyModpackContentField));
                }

                CompletableFuture.allOf(fetchFutures.toArray(new CompletableFuture[0])).get();

                FETCH_EXECUTOR.shutdown();
                try {
                    if (!FETCH_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                        FETCH_EXECUTOR.shutdownNow();
                        if (!FETCH_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                            LOGGER.error("FETCH Executor did not terminate");
                        }
                    }
                } catch (InterruptedException e) {
                    FETCH_EXECUTOR.shutdownNow();
                }

                LOGGER.info("Fetches took {}ms", System.currentTimeMillis() - startTime);
            } else {
                LOGGER.warn("APIs are down, skipping fetches");
            }


            wholeQueue = serverModpackContent.list.size();

            LOGGER.info("In queue left {} files to download ({}kb)", wholeQueue, totalBytesToDownload / 1024);

            if (wholeQueue > 0) {

                ThreadFactory threadFactoryDownloads = new ThreadFactoryBuilder()
                        .setNameFormat("AutoModpackDownload-%d")
                        .build();

                DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(
                        MAX_DOWNLOADS,
                        threadFactoryDownloads
                );

                for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : serverModpackContent.list) {
                    while (downloadFutures.size() >= MAX_DOWNLOADS) { // Async Setting - max `some` download at the same time
                        downloadFutures = downloadFutures.stream()
                                .filter(future -> !future.isDone())
                                .collect(Collectors.toList());
                    }

                    String fileName = modpackContentField.file;
                    String serverSHA1 = modpackContentField.sha1;

                    Path downloadFile = Paths.get(modpackDir + File.separator + fileName);
                    String url;
                    if (modpackContentField.link.startsWith("/")) { // AutoModpack host
                        url = link + Url.encode(modpackContentField.link); // We need to change things like [ ] to %5B %5D etc.
                    } else { // Other host
                        url = modpackContentField.link; // This link just must work, so we don't need to encode it
                    }

                    downloadFutures.add(downloadAsync(url, downloadFile.toAbsolutePath().normalize(), serverSHA1));
                }

                CompletableFuture.allOf(downloadFutures.toArray(new CompletableFuture[0])).get();

                terminateDownloadExecutor();
            }

            // Downloads completed
            Files.write(modpackContentFile, serverModpackContentByteArray);
            finishModpackUpdate(modpackDir, modpackContentFile);

            if (AudioManager.isMusicPlaying()) {
                AudioManager.stopMusic();
            }

            if (!failedDownloads.isEmpty()) {
                StringBuilder failedFiles = new StringBuilder();
                for (Map.Entry<String, String> entry : failedDownloads.entrySet()) {
                    LOGGER.error("Failed to download: " + entry.getKey() + " from " + entry.getValue());
                    failedFiles.append(entry.getKey());
                }
                ScreenTools.setTo.error("automodpack.error.files", "Failed to download: " + failedFiles, "automodpack.error.logs");

                if (preload && update) {
                    LOGGER.warn("Update completed with errors! Took: " + (System.currentTimeMillis() - start) + " ms");
                    new ReLauncher.Restart(modpackDir, fullDownload);
                }

                return;
            }

            if (update) {
                LOGGER.info("Update completed! Took: " + (System.currentTimeMillis() - start) + " ms");
                new ReLauncher.Restart(modpackDir, fullDownload);
            } else if (ScreenTools.getScreenString().contains("dangerscreen") || ScreenTools.getScreenString().contains("downloadscreen") || ScreenTools.getScreenString().contains("disconnectedscreen")) {
                new ReLauncher.Restart(modpackDir, fullDownload);
            }

            LOGGER.info("Modpack is up-to-date! Took: " + (System.currentTimeMillis() - start) + " ms");

        } catch (SocketTimeoutException | ConnectException e) {
            LOGGER.error("Modpack host of " + link + " is not responding", e);
        } catch (Exception e) {
            ScreenTools.setTo.error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
            e.printStackTrace();
        }
    }


    private static CompletableFuture<Void> downloadAsync(String url, Path downloadFile, String serverSHA1) {
        return CompletableFuture.runAsync(() -> downloadFile(url, downloadFile, serverSHA1), DOWNLOAD_EXECUTOR);
    }

    private static void downloadFile(String url, Path downloadFile, String serverSHA1) {
        if (!update || !ScreenTools.getScreenString().contains("downloadscreen")) {
            ScreenTools.setTo.download();
        }

        update = true;
        DownloadInfo downloadInfo = new DownloadInfo(downloadFile.getFileName().toString());
        downloadInfos.add(downloadInfo);

        int maxAttempts = 3;
        int attempts = 0;
        boolean success = false;
        long startTime = System.currentTimeMillis();

        while (attempts < maxAttempts && !success) {
            attempts++;
            LOGGER.info("Downloading {}... (attempt {})", downloadFile.getFileName(), attempts);
            LOGGER.info("URL: {}", url);

            try {
                Download downloadInstance = new Download();
                downloadInstance.download(url, downloadFile, downloadInfo);

                String localSHA1 = CustomFileUtils.getHash(downloadFile, "SHA-1");

                if (serverSHA1.equals(localSHA1)) {
                    success = true;
                } else {
                    if (attempts != maxAttempts) {
                        LOGGER.warn("Hashes do not match, retrying... client: {} server: {}", localSHA1, serverSHA1);
                    }
                    CustomFileUtils.forceDelete(downloadFile);
                    totalBytesDownloaded -= downloadInstance.getTotalBytesRead();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        downloadInfos.remove(downloadInfo);
        if (success) {
            LOGGER.info("{} downloaded successfully in {}ms", downloadFile.getFileName(), (System.currentTimeMillis() - startTime));
            changelogList.put(downloadFile.getFileName().toString(), true);
            alreadyDownloaded++;
        } else {

            // Download from our server if we can't download from mod platforms
            List<Jsons.ModpackContentFields.ModpackContentItem> list = CustomFileUtils.byteArrayToArrayList(serverModpackContentByteArray);
            String serverUrl = list.stream().filter(modpackContentField -> modpackContentField.sha1.equals(serverSHA1)).findFirst().get().link;

            if (!url.equals(serverUrl)) {
                LOGGER.info("Couldn't download from {}. Downloading {} from {}", url, downloadFile.getFileName(), serverUrl);
                downloadFile(serverUrl, downloadFile, serverSHA1);
                return;
            }

            failedDownloads.put(downloadFile.getFileName().toString(), url);
            LOGGER.error("Failed to download {} after {} attempts", downloadFile.getFileName(), attempts);
        }
    }

    private static CompletableFuture<Void> fetchAsync(Jsons.ModpackContentFields.ModpackContentItem copyModpackContentField) {
        return CompletableFuture.runAsync(() -> fetchModPlatforms(copyModpackContentField), FETCH_EXECUTOR);
    }

    private static void fetchModPlatforms(Jsons.ModpackContentFields.ModpackContentItem copyModpackContentField) {
        String fileType = copyModpackContentField.type;

        // Check if the file is mod, shaderpack or resourcepack is available to download from modrinth or curseforge
        if (fileType.equals("mod") || fileType.equals("shaderpack") || fileType.equals("resourcepack")) {
            String serverSHA1 = copyModpackContentField.sha1;
            String serverMurmur = copyModpackContentField.murmur;
            long fileSize = Long.parseLong(copyModpackContentField.size);

            if (!ScreenTools.getScreenString().contains("fetchscreen")) {
                ScreenTools.setTo.fetch();
            }

            String modPlatformUrl = tryModPlatforms(serverSHA1, serverMurmur, fileSize);
            if (modPlatformUrl != null && !modPlatformUrl.isEmpty()) {
                copyModpackContentField.link = modPlatformUrl;
                totalFetchedFiles++;
            }
        }
    }

    private static void finishModpackUpdate(Path modpackDir, Path modpackContentFile) throws Exception {
        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

        if (modpackContent == null) {
            LOGGER.error("Modpack content is null");
            return;
        }

        // clear empty directories
        CustomFileUtils.deleteEmptyFiles(modpackDir, true, modpackContent.list);
        Path directory = Paths.get("./");
        CustomFileUtils.deleteEmptyFiles(directory, false, modpackContent.list);

        checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");

        // make a list of editable files if they do not exist in changelog
        List<String> editableFiles = new ArrayList<>();
        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : modpackContent.list) {

            String fileName = Paths.get(modpackContentField.file).getFileName().toString();

            if (changelogList.containsKey(fileName)) {
                continue;
            }

            if (modpackContentField.editable) {
                editableFiles.add(modpackContentField.file);
            }
        }


        // copy files to running directory
        // map running dir files
        List<Path> filesBefore = mapAllFiles(directory, new ArrayList<>());
        ModpackUtils.copyModpackFilesFromModpackDirToRunDir(modpackDir, modpackContent, editableFiles);
        if (!mapAllFiles(directory, new ArrayList<>()).equals(filesBefore)) {
            update = true;
        }

        List<String> files = new ArrayList<>();
        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : modpackContent.list) {
            String fileName = Paths.get(modpackContentField.file).getFileName().toString();
            files.add(fileName);
        }

        try (Stream<Path> stream = Files.walk(modpackDir, 10)) {
            stream.filter(file -> !Files.isDirectory(file))
                    .filter(file -> !file.equals(modpackContentFile))
                    .forEach(file -> {
                        String fileName = file.getFileName().toString();
                        if (!files.contains(fileName)) {
                            Path fileInRunningDir = Paths.get("." + file.toString().replace(modpackDir.toString(), ""));
                            try {
                                if (Files.exists(fileInRunningDir) && CustomFileUtils.compareFileHashes(file, fileInRunningDir, "SHA-1")) {
                                    LOGGER.info("Deleting {} and {}", file, fileInRunningDir);
                                    CustomFileUtils.forceDelete(fileInRunningDir);
                                } else {
                                    LOGGER.info("Deleting {}", file);
                                }
                            } catch (IOException | NoSuchAlgorithmException e) {
                                LOGGER.error("An error occurred while trying to compare file hashes", e);
                                e.printStackTrace();
                            }
                            CustomFileUtils.forceDelete(file);
                            changelogList.put(fileName, false);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("An error occurred while trying to walk through the files in the modpack directory", e);
            e.printStackTrace();
        }

        // There is a possibility that some files are in running directory, but not in modpack dir
        // Because they were already downloaded before
        // So copy files to modpack dir
        ModpackUtils.copyModpackFilesFromRunDirToModpackDir(modpackDir, modpackContent, editableFiles);

        ModpackUtils.copyModpackFilesFromModpackDirToRunDir(modpackDir, modpackContent, editableFiles);

        checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");
    }

    private static String tryModPlatforms(String sha512, String murmur, long fileSize) {

        if (modrinthAPI) {
            ModrinthAPI modrinthFileInfo = ModrinthAPI.getModInfoFromSHA512(sha512);
            if (modrinthFileInfo != null) {
                LOGGER.info("Found {} on Modrinth downloading from there", modrinthFileInfo.fileName);
                return modrinthFileInfo.downloadUrl;
            }
        }

        if (curseforgeAPI) {
            CurseForgeAPI curseforgeFileInfo = CurseForgeAPI.getModInfoFromMurmur(murmur, fileSize);
            if (curseforgeFileInfo != null) {
                LOGGER.info("Found {} on CurseForge downloading from there", curseforgeFileInfo.fileName);
                return curseforgeFileInfo.downloadUrl;
            }
        }

        return null;
    }


    private static boolean APIsUp() {
        String[] urls = {
                "https://api.modrinth.com/",
                "https://api.curseforge.com/"
        };

        return Arrays.stream(urls).parallel().anyMatch(url -> pingURL(url, 3000));
    }

    public static boolean pingURL(String url, int timeout) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            if (responseCode != 200) {
                if (url.contains("modrinth")) {
                    modrinthAPI = false;
                    LOGGER.warn("Modrinth API is down!");
                } else if (url.contains("curseforge")) {
                    curseforgeAPI = false;
                    LOGGER.warn("Curseforge API is down!");
                }
                return false;
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }


    // This method cancels the current download by interrupting the thread pool
    public static void cancelDownload() {
        try {
            LOGGER.info("Cancelling download for " + downloadFutures.size() + " files...");
            downloadFutures.forEach(future -> future.cancel(true));

            terminateDownloadExecutor();

            downloadFutures.clear();
            downloadInfos.clear();
            DOWNLOAD_EXECUTOR = null;
            totalFetchedFiles = 0;
            totalBytesDownloaded = 0;
            alreadyDownloaded = 0;
            failedDownloads.clear();
            changelogList.clear();
            update = false;

            LOGGER.info("Download canceled");

            if (ScreenTools.getScreenString().contains("downloadscreen")) {
                ScreenTools.setTo.title();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void terminateDownloadExecutor() {
        DOWNLOAD_EXECUTOR.shutdown();
        try {
            if (!DOWNLOAD_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                DOWNLOAD_EXECUTOR.shutdownNow();
                if (!DOWNLOAD_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                    LOGGER.error("CREATION Executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            DOWNLOAD_EXECUTOR.shutdownNow();
        }
    }

    public static DownloadInfo getDownloadInfo(String name) {
        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);
        for (DownloadInfo downloadInfo : downloadInfosCopy) { // this is done like that to avoid ConcurrentModificationException
            if (downloadInfo == null || downloadInfo.getFileName() == null) continue;
            if (downloadInfo.getFileName().equals(name)) {
                return downloadInfo;
            }
        }
        return null;
    }

    // removes mods from main mods folder that are having the same id as the ones in the modpack mods folder but different version/hash
    private static void checkAndRemoveDuplicateMods(String modpackModsFile) {
        Map<String, String> mainMods = getMods("./mods/");
        Map<String, String> modpackMods = getMods(modpackModsFile);

        if (mainMods == null || modpackMods == null) return;

        if (!hasDuplicateValues(mainMods)) return;

        for (Map.Entry<String, String> mainMod : mainMods.entrySet()) {
            String mainModFileName = mainMod.getKey();
            String mainModId = mainMod.getValue();

            if (mainModId == null || mainModFileName == null) {
                continue;
            }

            for (Map.Entry<String, String> modpackMod : modpackMods.entrySet()) {
                String modpackModFileName = modpackMod.getKey();
                String modpackModId = modpackMod.getValue();

                if (modpackModId == null || modpackModFileName == null) {
                    continue;
                }

                if (mainModId.equals(modpackModId) && !mainModFileName.equals(modpackModFileName)) {
                    Path mainModFile = Paths.get("./mods/" + mainModFileName);
                    LOGGER.info("Deleting {} from main mods folder...", mainModFile.getFileName());
                    CustomFileUtils.forceDelete(mainModFile);
                    break;
                }
            }
        }
    }

    private static Map<String, String> getMods(String modsDir) {
        Map<String, String> defaultMods = new HashMap<>();
        Path defaultModsDir = Paths.get(modsDir);

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(defaultModsDir)) {
            for (Path defaultMod : directoryStream) {
                if (!Files.isRegularFile(defaultMod) || !defaultMod.getFileName().toString().endsWith(".jar")) {
                    continue;
                }
                defaultMods.put(defaultMod.getFileName().toString(), JarUtilities.getModIdFromJar(defaultMod, true));
            }
        } catch (IOException e) {
            return null;
        }

        return defaultMods;
    }

    private static boolean hasDuplicateValues(Map<String, String> map) {
        Set<String> values = new HashSet<>();
        for (String value : map.values()) {
            if (values.contains(value)) {
                return true;
            }
            values.add(value);
        }
        return false;
    }

}