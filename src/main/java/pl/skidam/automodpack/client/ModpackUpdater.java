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

import com.google.gson.JsonObject;
import pl.skidam.automodpack.utils.DownloadManager;
import pl.skidam.automodpack.utils.FetchManager;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.utils.*;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static pl.skidam.automodpack.GlobalVariables.*;
import static pl.skidam.automodpack.config.ConfigTools.GSON;
import static pl.skidam.automodpack.utils.CustomFileUtils.mapAllFiles;

@SuppressWarnings("unchecked")
public class ModpackUpdater {
    public static Map<String, String> changesAddedList = new HashMap<>(); // <file name, main page url>
    public static Map<String, String> changesDeletedList = new HashMap<>(); // <file name, main page url>
    private static ExecutorService DOWNLOAD_EXECUTOR;
    public static DownloadManager downloadManager;
    public static FetchManager fetchManager;
    public static long totalBytesToDownload = 0;
    public static boolean fullDownload = false;
    private static Jsons.ModpackContentFields serverModpackContent;
    public static Map<String, String> failedDownloads = new HashMap<>(); // <file, url>
    private static byte[] serverModpackContentByteArray = new byte[0];

    public static String getModpackName() {
        return serverModpackContent.modpackName;
    }

    public ModpackUpdater(Jsons.ModpackContentFields serverModpackContent, String link, Path modpackDir) {
        if (link == null || link.isEmpty() || modpackDir.toString().isEmpty()) return;

        try {
            Path modpackContentFile = Paths.get(modpackDir + File.separator + "modpack-content.json");
            Path workingDirectory = Paths.get("./");

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
            serverModpackContentByteArray = GSON.toJson(serverModpackContent).getBytes();

            if (!Files.exists(modpackDir)) {
                Files.createDirectories(modpackDir);
            }

            if (Files.exists(modpackContentFile)) {
                if ("false".equals(ModpackUtils.isUpdate(serverModpackContent, modpackDir))) {
                    // check if modpack is loaded now loaded

                    LOGGER.info("Modpack is up to date");

                    CheckAndLoadModpack(modpackDir, modpackContentFile, workingDirectory);
                    return;
                }
            } else if (!preload && ScreenTools.getScreen() != null) {

                fullDownload = true;

                CompletableFuture.runAsync(() -> {
                    while (!ScreenTools.getScreenString().contains("dangerscreen")) {
                        ScreenTools.setTo.danger(ScreenTools.getScreen(), link, modpackDir, modpackContentFile);
                        new Wait(50);
                    }
                });
                return;
            }

            LOGGER.warn("Modpack update found");

            ScreenTools.setTo.download();

            ModpackUpdaterMain(link, modpackDir, modpackContentFile);

        } catch (Exception e) {
            LOGGER.error("Error while initializing modpack updater");
            e.printStackTrace();
        }
    }

    private void CheckAndLoadModpack(Path modpackDir, Path modpackContentFile, Path workingDirectory) throws Exception {

        List<Path> filesBefore = mapAllFiles(workingDirectory, new ArrayList<>());

        List<Path> deletedFilesToIgnore = finishModpackUpdate(modpackDir, modpackContentFile);

        List<Path> filesAfter = mapAllFiles(workingDirectory, new ArrayList<>());

        if (deletedFilesToIgnore != null && !deletedFilesToIgnore.isEmpty()) {
            filesBefore.removeAll(deletedFilesToIgnore);
        }

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

        LOGGER.info("Added files: " + addedFiles);
        LOGGER.info("Deleted files: " + deletedFiles);

        new ReLauncher.Restart(modpackDir, fullDownload);
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

            Instant startFetching = Instant.now();

            fetchManager = new FetchManager();

            for (Jsons.ModpackContentFields.ModpackContentItem field : serverModpackContent.list) {

                totalBytesToDownload += Long.parseLong(field.size);

                String fileType = field.type;

                // Check if the file is mod, shaderpack or resourcepack is available to download from modrinth or curseforge
                if (fileType.equals("mod") || fileType.equals("shader") || fileType.equals("resourcepack")) {
                    fetchManager.fetch(field.link, field.sha1, field.murmur, field.size, fileType);
                }
            }

            fetchManager.joinAll();
            fetchManager.cancelAllAndShutdown();

            LOGGER.info("Finished fetching urls in {}ms", Duration.between(startFetching, Instant.now()).toMillis());


            int wholeQueue = serverModpackContent.list.size();

            LOGGER.info("In queue left {} files to download ({}kb)", wholeQueue, totalBytesToDownload / 1024);

            ScreenTools.setTo.download();

            if (wholeQueue > 0) {

                downloadManager = new DownloadManager(totalBytesToDownload);

                for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : serverModpackContent.list) {

                    String fileName = modpackContentField.file;
                    String serverSHA1 = modpackContentField.sha1;

                    Path downloadFile = Paths.get(modpackDir + File.separator + fileName);
                    String url;

                    String fileLink = modpackContentField.link;
                    if (fetchManager != null && fetchManager.fetchedData.containsKey(modpackContentField.sha1)) {
                        url = new URL(fetchManager.fetchedData.get(modpackContentField.sha1).getPlatformUrl()).toString();
                    } else if (modpackContentField.link.startsWith("/")) { // AutoModpack host
                        url = new URL(link + Url.encode(fileLink)).toString(); // We need to change things like [ ] to %5B %5D etc.
                    } else { // Other host
                        url = new URL(fileLink).toString(); // This link just must work, so we don't need to encode it
                    }

                    Runnable failureCallback = () -> {
                        LOGGER.error("Failed to download {} from {}", fileName, url);
                    };

                    Runnable successCallback = () -> {
                        LOGGER.info("Successfully downloaded {} from {}", fileName, url);

                        String mainPageUrl = null;
                        if (fetchManager != null && fetchManager.fetchedData.get(modpackContentField.sha1) != null) {
                            mainPageUrl = fetchManager.fetchedData.get(modpackContentField.sha1).getMainPageUrl();
                        }

                        changesAddedList.put(downloadFile.getFileName().toString(), mainPageUrl);
                    };


                    downloadManager.download(downloadFile, serverSHA1, url, successCallback, failureCallback);
                }

                downloadManager.joinAll();
                downloadManager.cancelAllAndShutdown();

                LOGGER.info("Finished downloading files in {}ms", Duration.between(startFetching, Instant.now()).toMillis());
            }

            // Downloads completed
            Files.write(modpackContentFile, serverModpackContentByteArray);
            finishModpackUpdate(modpackDir, modpackContentFile);

            // change loader and minecraft version in launchers like prism, multimc.
            MmcPackMagic.changeVersion(MmcPackMagic.modLoaderUIDs, serverModpackContent.loaderVersion); // update loader version
            MmcPackMagic.changeVersion(MmcPackMagic.mcVerUIDs, serverModpackContent.mcVersion); // update minecraft version

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

                LOGGER.warn("Update *completed* with ERRORS! Took: " + (System.currentTimeMillis() - start) + " ms");

                if (preload) {
                    new ReLauncher.Restart(modpackDir, fullDownload);
                }

                return;
            }

            LOGGER.info("Update completed! Took: " + (System.currentTimeMillis() - start) + " ms");

            new ReLauncher.Restart(modpackDir, fullDownload);

            LOGGER.info("Modpack is up-to-date! Took: " + (System.currentTimeMillis() - start) + " ms");

        } catch (SocketTimeoutException | ConnectException e) {
            LOGGER.error("Modpack host of " + link + " is not responding", e);
        } catch (Exception e) {
            ScreenTools.setTo.error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
            e.printStackTrace();
        }
    }

    private static List<Path> finishModpackUpdate(Path modpackDir, Path modpackContentFile) throws Exception {
        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

        if (modpackContent == null) {
            LOGGER.error("Modpack content is null");
            return null;
        }

        // clear empty directories
        List<Path> emptyFilesPaths = new ArrayList<>();
        List<Path> emptyList1 = CustomFileUtils.deleteEmptyFiles(modpackDir, true, modpackContent.list);
        List<Path> emptyList2 = CustomFileUtils.deleteEmptyFiles(Paths.get("./"), false, modpackContent.list);

        emptyFilesPaths.addAll(emptyList1);
        emptyFilesPaths.addAll(emptyList2);

        checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");

        // make a list of editable files if they do not exist in added changelog
        List<String> editableFiles = new ArrayList<>();
        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : modpackContent.list) {

            String fileName = Paths.get(modpackContentField.file).getFileName().toString();

            if (changesAddedList.containsKey(fileName)) {
                continue;
            }

            if (modpackContentField.editable) {
                editableFiles.add(modpackContentField.file);
            }
        }

        // copy files to running directory
        // map running dir files
        ModpackUtils.copyModpackFilesFromModpackDirToRunDir(modpackDir, modpackContent, editableFiles);

        List<String> files = new ArrayList<>();
        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : modpackContent.list) {
            String fileName = Paths.get(modpackContentField.file).getFileName().toString();
            files.add(fileName);
        }

        try (Stream<Path> stream = Files.walk(modpackDir)) {
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
                        changesDeletedList.put(fileName, null);
                    }
            });
        }

        // There is a possibility that some files are in running directory, but not in modpack dir
        // Because they were already downloaded before
        // So copy files to modpack dir
        ModpackUtils.copyModpackFilesFromRunDirToModpackDir(modpackDir, modpackContent, editableFiles);

        ModpackUtils.copyModpackFilesFromModpackDirToRunDir(modpackDir, modpackContent, editableFiles);

        checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");

        return emptyFilesPaths;
    }


    // This method cancels the current download by interrupting the thread pool
    public static void cancelDownload() {
        try {
            fetchManager.cancelAllAndShutdown();
            downloadManager.cancelAllAndShutdown();

            terminateDownloadExecutor();

            DOWNLOAD_EXECUTOR = null;
            failedDownloads.clear();
            changesAddedList.clear();
            changesDeletedList.clear();

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

    // removes mods from the main mods folder
    // that are having the same id as the ones in the modpack mods folder but different version/hash
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