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
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static pl.skidam.automodpack.StaticVariables.*;
import static pl.skidam.automodpack.config.ConfigTools.GSON;
import static pl.skidam.automodpack.utils.CustomFileUtils.mapAllFiles;
import static pl.skidam.automodpack.utils.RefactorStrings.getETA;

public class ModpackUpdater {
    public static List<DownloadInfo> downloadInfos = new ArrayList<>();
    public static final int MAX_DOWNLOADS = 5; // at the same time
    public static final int MAX_FETCHES = 20; // at the same time
    public static boolean modrinth = true;
    public static boolean curseforge = true;
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
    private static Jsons.ModpackContentFields serverModpackContent;
    public static Map<String, String> failedDownloads = new HashMap<>(); // <file, url>

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

        return getETA(totalETA);
    }

    public static String getModpackName() {
        return serverModpackContent.modpackName;
    }

    public ModpackUpdater(Jsons.ModpackContentFields serverModpackContent, String link, File modpackDir) {
        if (link == null || link.isEmpty() || modpackDir.toString() == null || modpackDir.toString().isEmpty()) return;

        try {
            ModpackUpdater.serverModpackContent = serverModpackContent;

            if (serverModpackContent == null)  { // server is down, or you don't have access to internet, but we still want to load selected modpack

                LOGGER.warn("Server is down, or you don't have access to internet, but we still want to load selected modpack");

                File modpackContentFile = new File(modpackDir + File.separator + "modpack-content.json");

                if (!modpackContentFile.exists()) return;

                Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);
                if (modpackContent == null) return;

                List<File> filesBefore = mapAllFiles(modpackDir, new ArrayList<>());

                finishModpackUpdate(modpackDir, modpackContentFile);

                List<File> filesAfter = mapAllFiles(modpackDir, new ArrayList<>());

                if (filesAfter.equals(filesBefore)) {
                    LOGGER.info("Modpack is already loaded");
                    return;
                }

                // print out what files were added, deleted, updated
                List<File> addedFiles = filesAfter.stream().filter(file -> !filesBefore.contains(file)).toList();
                List<File> deletedFiles = filesBefore.stream().filter(file -> !filesAfter.contains(file)).toList();
                // print it
                LOGGER.info("Added files: " + addedFiles);
                LOGGER.info("Deleted files: " + deletedFiles);

                new ReLauncher.Restart(modpackDir);

                return;
            }

            serverModpackContent.link = link;

            if (!modpackDir.exists()) modpackDir.mkdirs();

            File modpackContentFile = new File(modpackDir + File.separator + "modpack-content.json");

            if (modpackContentFile.exists()) {
                if (!ModpackUtils.isUpdate(serverModpackContent, modpackDir)) {
                    // check if modpack is loaded now loaded

                    LOGGER.info("Modpack is up to date");

                    List<File> filesBefore = mapAllFiles(modpackDir, new ArrayList<>());

                    finishModpackUpdate(modpackDir, modpackContentFile);

                    List<File> filesAfter = mapAllFiles(modpackDir, new ArrayList<>());

                    if (filesAfter.equals(filesBefore)) {
                        LOGGER.info("Modpack is already loaded");
                        return;
                    }

                    // print out what files were added, deleted, updated
                    List<File> addedFiles = filesAfter.stream().filter(file -> !filesBefore.contains(file)).toList();
                    List<File> deletedFiles = filesBefore.stream().filter(file -> !filesAfter.contains(file)).toList();
                    // print it
                    LOGGER.info("Added files: " + addedFiles);
                    LOGGER.info("Deleted files: " + deletedFiles);

                    new ReLauncher.Restart(modpackDir);

                    return;
                }
            } else if (!preload && ScreenTools.getScreen() != null) {
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

    public static void ModpackUpdaterMain(String link, File modpackDir, File modpackContentFile) {

        long start = System.currentTimeMillis();

        try {

            if (quest) {
                String modsPathString = modsPath.toString().substring(1) + "/";
                LOGGER.info("Quest mode is enabled, changing /mods/ path to {}", modsPathString);
                for (Jsons.ModpackContentFields.ModpackContentItems modpackContentField : serverModpackContent.list) {
                    if (modpackContentFile.toString().startsWith("/mods/")) {
                        modpackContentField.file = modpackContentField.file.replaceFirst("/mods/", modsPathString);
                    }
                }
            }


            byte[] serverModpackContentByteArray = GSON.toJson(serverModpackContent).getBytes();
            List<Jsons.ModpackContentFields.ModpackContentItems> copyModpackContentList = new ArrayList<>(serverModpackContent.list);

            for (Jsons.ModpackContentFields.ModpackContentItems modpackContentField : serverModpackContent.list) {
                String fileName = modpackContentField.file;
                String serverSHA1 = modpackContentField.sha1;

                File file = new File(modpackDir + File.separator + fileName);

                if (!file.exists()) {
                    file = new File("./" + fileName);
                }

                if (!file.exists()) {
                    continue;
                }

                if (serverSHA1.equals(CustomFileUtils.getHashWithRetry(file, "SHA-1"))) {
                    LOGGER.info("Skipping already downloaded file: " + fileName);
                    copyModpackContentList.remove(modpackContentField);
                } else if (modpackContentField.isEditable) {
                    LOGGER.info("Skipping editable file: " + fileName);
                    copyModpackContentList.remove(modpackContentField);
                } else if (file.isFile() && !modpackContentField.type.equals("mod")) {
                    if (file.length() == Long.parseLong(modpackContentField.size)) {
                        LOGGER.info("Skipping* already downloaded file: " + fileName);
                        copyModpackContentList.remove(modpackContentField);
                    }
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

                for (Jsons.ModpackContentFields.ModpackContentItems copyModpackContentField : copyModpackContentList) {
                    while (fetchFutures.size() >= MAX_FETCHES) { // Async Setting - max `some` fetches at the same time
                        fetchFutures = fetchFutures.stream()
                                .filter(future -> !future.isDone())
                                .collect(Collectors.toList());
                    }

                    totalBytesToDownload += Long.parseLong(copyModpackContentField.size);

                    fetchFutures.add(fetchAsync(copyModpackContentField));
                }

                CompletableFuture.allOf(fetchFutures.toArray(new CompletableFuture[0])).get();

                LOGGER.info("Fetches took {}ms", System.currentTimeMillis() - startTime);
            } else {
                LOGGER.warn("APIs are down, skipping fetches");
            }


            wholeQueue = copyModpackContentList.size();

            LOGGER.info("In queue left {} files to download ({}kb)", wholeQueue, totalBytesToDownload / 1024);

            if (wholeQueue > 0) {

                ThreadFactory threadFactoryDownloads = new ThreadFactoryBuilder()
                        .setNameFormat("AutoModpackDownload-%d")
                        .build();

                DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(
                        MAX_DOWNLOADS,
                        threadFactoryDownloads
                );

                for (Jsons.ModpackContentFields.ModpackContentItems modpackContentField : copyModpackContentList) {
                    while (downloadFutures.size() >= MAX_DOWNLOADS) { // Async Setting - max `some` download at the same time
                        downloadFutures = downloadFutures.stream()
                                .filter(future -> !future.isDone())
                                .collect(Collectors.toList());
                    }

                    String fileName = modpackContentField.file;
                    String serverSHA1 = modpackContentField.sha1;

                    File downloadFile = new File(modpackDir + File.separator + fileName);
                    String url;
                    if (modpackContentField.link.startsWith("/")) { // AutoModpack host
                        url = link + modpackContentField.link;
                        url = Url.encode(url); // We need to change things like [ ] to %5B %5D etc.
                    } else { // Other host
                        url = modpackContentField.link; // This link just must work, so we don't need to encode it
                    }

                    downloadFutures.add(downloadAsync(url, downloadFile, serverSHA1));
                }

                CompletableFuture.allOf(downloadFutures.toArray(new CompletableFuture[0])).get();
            }

            // Downloads completed
            Files.write(modpackContentFile.toPath(), serverModpackContentByteArray);
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
                ScreenTools.setTo.error("Failed to download some files", "Failed to download: " + failedFiles, "More details in logs.");

                if (preload && update) {
                    LOGGER.warn("Update completed with errors! Took: " + (System.currentTimeMillis() - start) + " ms");
                    new ReLauncher.Restart(modpackDir);
                }

                return;
            }

            if (update) {
                LOGGER.info("Update completed! Took: " + (System.currentTimeMillis() - start) + " ms");
                new ReLauncher.Restart(modpackDir);
            }

            LOGGER.info("Modpack is up-to-date! Took: " + (System.currentTimeMillis() - start) + " ms");

        } catch (SocketTimeoutException | ConnectException e) {
            LOGGER.error("Modpack host of " + link + " is not responding", e);
        } catch (Exception e) {
            ScreenTools.setTo.error("Critical error while downloading modpack.", "\"" + e.getMessage() + "\"", "More details in logs.");
            e.printStackTrace();
        }
    }

    private static void finishModpackUpdate(File modpackDir, File modpackContentFile) throws Exception {
        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

        if (modpackContent == null) {
            LOGGER.error("Modpack content is null");
            return;
        }

        // clear empty directories
        CustomFileUtils.deleteEmptyFiles(modpackDir, true, modpackContent.list);
        CustomFileUtils.deleteEmptyFiles(new File("./"), false, modpackContent.list);

        checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");

        // make list of editable files if they do not exist in changelog
        List<String> editableFiles = new ArrayList<>();
        for (Jsons.ModpackContentFields.ModpackContentItems modpackContentField : modpackContent.list) {

            String fileName = new File(modpackContentField.file).getName();

            if (changelogList.containsKey(fileName)) {
                continue;
            }

            if (modpackContentField.isEditable) {
                editableFiles.add(modpackContentField.file);
            }
        }


        // copy files to running directory
        // map running dir files
        List<File> filesBefore = mapAllFiles(new File("./"), new ArrayList<>());
        ModpackUtils.copyModpackFilesFromModpackDirToRunDir(modpackDir, modpackContent, editableFiles);
        if (!mapAllFiles(new File("./"), new ArrayList<>()).equals(filesBefore)) {
            update = true;
        }

        List<String> files = modpackContent.list.stream().map(modpackContentField -> new File(modpackContentField.file).getName()).toList();

        try (Stream<Path> stream = Files.walk(modpackDir.toPath(), 10)) {
            for (Path file : stream.toList()) {
                if (Files.isDirectory(file)) continue;
                if (file.equals(modpackContentFile.toPath())) continue;
                if (!files.contains(file.toFile().getName())) {

                    File fileInRunningDir = new File("." + file.toFile().toString().replace(modpackDir.toString(), ""));
//                    LOGGER.warn("File in running dir: " + fileInRunningDir + " exists: " + fileInRunningDir.exists() + " hash same? " + CustomFileUtils.compareFileHashes(file.toFile(), fileInRunningDir, "SHA-256"));
                    if (fileInRunningDir.exists() && CustomFileUtils.compareFileHashes(file.toFile(), fileInRunningDir, "SHA-1")) {
                        LOGGER.info("Deleting {} and {}", file.toFile(), fileInRunningDir);
                        CustomFileUtils.forceDelete(fileInRunningDir, true);
                    } else {
                        LOGGER.info("Deleting " + file.toFile());
                    }

                    CustomFileUtils.forceDelete(file.toFile(), true);
                    changelogList.put(file.toFile().getName(), false);
                }
            }
        } catch (Exception e) {
            LOGGER.error("An error occurred while trying to walk through the files in the modpack directory", e);
        }


        // There is possibility that some files are in running directory, but not in modpack dir
        // Because they were already downloaded before
        // So copy files to modpack dir
        ModpackUtils.copyModpackFilesFromRunDirToModpackDir(modpackDir, modpackContent, editableFiles);


        ModpackUtils.copyModpackFilesFromModpackDirToRunDir(modpackDir, modpackContent, editableFiles);

        checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");
    }


    private static CompletableFuture<Void> downloadAsync(String url, File downloadFile, String serverSHA1) {
        return CompletableFuture.runAsync(() -> downloadFile(url, downloadFile, serverSHA1), DOWNLOAD_EXECUTOR);
    }

    private static void downloadFile(String url, File downloadFile, String serverSHA1) {
        if (!update || !ScreenTools.getScreenString().contains("downloadscreen")) {
            ScreenTools.setTo.download();
        }

        update = true;
        DownloadInfo downloadInfo = new DownloadInfo(downloadFile.getName());
        downloadInfos.add(downloadInfo);

        int maxAttempts = 3;
        int attempts = 0;
        boolean success = false;
        long startTime = System.currentTimeMillis();

        while (attempts < maxAttempts && !success) {
            attempts++;
            LOGGER.info("Downloading {}... (attempt {})", downloadFile.getName(), attempts);
            LOGGER.info("URL: {}", url);

            try {
                Download downloadInstance = new Download();
                downloadInstance.download(url, downloadFile, downloadInfo);

                String localSHA1 = CustomFileUtils.getHashWithRetry(downloadFile, "SHA-1");

                long size = downloadInstance.getFileSize();

                if (serverSHA1.equals(localSHA1)) {
                    success = true;
                } else if (attempts == maxAttempts && !downloadFile.toString().endsWith(".jar") && downloadFile.length() == size) {
                    // FIXME: it shouldn't even return wrong hashes if the size is correct...
                    LOGGER.warn("Hashes of {} do not match, but size is correct so we will assume it is correct lol", downloadFile.getName());
                    success = true;
                } else {
                    if (attempts != maxAttempts) {
                        LOGGER.warn("Hashes do not match, retrying... client: {} server: {}", localSHA1, serverSHA1);
                    }
                    CustomFileUtils.forceDelete(downloadFile, false);
                    totalBytesDownloaded -= downloadInstance.getTotalBytesRead();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        downloadInfos.remove(downloadInfo);
        if (success) {
            LOGGER.info("{} downloaded successfully in {}ms", downloadFile.getName(), (System.currentTimeMillis() - startTime));
            changelogList.put(downloadFile.getName(), true);
            alreadyDownloaded++;
        } else {

            // Download from our server if we can't download from mod platforms
            String serverUrl = serverModpackContent.list.stream().filter(modpackContentField -> modpackContentField.sha1.equals(serverSHA1)).findFirst().get().link;
            if (!url.equals(serverUrl)) {
                downloadFile(serverUrl, downloadFile, serverSHA1);
                return;
            }

            failedDownloads.put(downloadFile.getName(), url);
            LOGGER.error("Failed to download {} after {} attempts", downloadFile.getName(), attempts);
        }
    }

    private static CompletableFuture<Void> fetchAsync(Jsons.ModpackContentFields.ModpackContentItems copyModpackContentField) {
        return CompletableFuture.runAsync(() -> fetchModPlatforms(copyModpackContentField), FETCH_EXECUTOR);
    }

    private static void fetchModPlatforms(Jsons.ModpackContentFields.ModpackContentItems copyModpackContentField) {
        String fileType = copyModpackContentField.type;

        // Check if file is mod, shaderpack or resourcepack is available to download from modrinth or curseforge
        if (fileType.equals("mod") || fileType.equals("shaderpack") || fileType.equals("resourcepack")) {
            String serverSHA1 = copyModpackContentField.sha1;
            String serverMurmur = copyModpackContentField.murmur;

            if (!ScreenTools.getScreenString().contains("fetchscreen")) {
                ScreenTools.setTo.fetch();
            }

            String modPlatformUrl = tryModPlatforms(serverSHA1, serverMurmur);
            if (modPlatformUrl != null && !modPlatformUrl.isEmpty()) {
                copyModpackContentField.link = modPlatformUrl;
                totalFetchedFiles++;
            }
        }
    }

    private static String tryModPlatforms(String sha512, String murmur) {

        if (modrinth) {
            ModrinthAPI modrinthFileInfo = ModrinthAPI.getModInfoFromSHA512(sha512);
            if (modrinthFileInfo != null) {
                LOGGER.info("Found {} on Modrinth downloading from there", modrinthFileInfo.fileName);
                return modrinthFileInfo.downloadUrl;
            }
        }

        if (curseforge) {
            CurseForgeAPI curseforgeFileInfo = CurseForgeAPI.getModInfoFromMurmur(murmur);
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
                    modrinth = false;
                    System.out.println("Modrinth is down!");
                } else if (url.contains("curseforge")) {
                    curseforge = false;
                    System.out.println("Curseforge is down!");
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
            DOWNLOAD_EXECUTOR.shutdownNow();

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

            if (ScreenTools.getScreenString().contains("download")) {
                ScreenTools.setTo.title();
            }
        } catch (Exception e) {
            e.printStackTrace();
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
                    File mainModFile = new File("./mods/" + mainModFileName);
                    LOGGER.info("Deleting {} from main mods folder...", mainModFile.getName());
                    CustomFileUtils.forceDelete(mainModFile, true);
                    break;
                }
            }
        }
    }

    private static Map<String, String> getMods(String modsDir) {
        Map<String, String> defaultMods = new HashMap<>();
        File defaultModsFolder = new File(modsDir);
        File[] defaultModsFiles = defaultModsFolder.listFiles();
        if (defaultModsFiles == null) return null;
        for (File defaultMod : defaultModsFiles) {
            if (!defaultMod.isFile() || !defaultMod.getName().endsWith(".jar")) continue;
            defaultMods.put(defaultMod.getName(), JarUtilities.getModIdFromJar(defaultMod, true));
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