package pl.skidam.automodpack.client;

import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.Download;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.utils.*;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static pl.skidam.automodpack.AutoModpack.LOGGER;
import static pl.skidam.automodpack.AutoModpack.automodpackDir;
import static pl.skidam.automodpack.config.ConfigTools.GSON;

public class ModpackUpdater {
    public static List<DownloadInfo> downloadInfos = new ArrayList<>();
    public static final int MAX_DOWNLOADS = 5; // at the same time
    public static List<CompletableFuture<Void>> downloadFutures = new ArrayList<>();
    public static Map<String, Boolean> changelogList = new HashMap<>(); // <file, true - downloaded, false - deleted>
    private static ExecutorService DOWNLOAD_EXECUTOR;
    public static boolean update;
    public static long totalBytesDownloaded = 0;
    private static int alreadyDownloaded = 0;
    private static int wholeQueue = 0;
    private static Config.ModpackContentFields serverModpackContent;
    public static Map<String, String> failedDownloads = new HashMap<>(); // <file, url>

    public static String getStage() {
        return alreadyDownloaded + "/" + wholeQueue;
    }

    public static int getTotalPercentageOfFileSizeDownloaded() {
        long totalBytes = 0;
        for (Config.ModpackContentFields.ModpackContentItems list : serverModpackContent.list) {
            if (!list.size.matches("^[0-9]*$")) continue;
            totalBytes += Long.parseLong(list.size);
        }
        return (int) ((double) totalBytesDownloaded / (double) totalBytes * 100);
    }

    public static double getTotalDownloadSpeed() {
        double totalSpeed = 0;
        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);
        for (DownloadInfo downloadInfo : downloadInfosCopy) { // this is done like that to avoid ConcurrentModificationException
            if (downloadInfo == null) continue;
            totalSpeed += downloadInfo.getDownloadSpeed();
        }
        if (!String.valueOf(totalSpeed).matches("^[0-9]*(\\.[0-9]+)?$")) return -1;
        try {
            if (totalSpeed <= 0) return 0;
            return Math.round(totalSpeed * 10.0) / 10.0;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String getTotalETA() {
        double totalBytesPerSecond = 0;
        long totalBytes = 0;

        for (Config.ModpackContentFields.ModpackContentItems list : serverModpackContent.list) {
            if (!list.size.matches("^[0-9]*$")) continue;
            totalBytes += Long.parseLong(list.size);
        }

        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);
        for (DownloadInfo downloadInfo : downloadInfosCopy) { // this is done like that to avoid ConcurrentModificationException
            if (downloadInfo == null) continue;
            totalBytesPerSecond += downloadInfo.getBytesPerSecond();
        }

        if (totalBytesPerSecond <= 0) return "N/A";

        double totalETA = (totalBytes - totalBytesDownloaded) / totalBytesPerSecond;

        int hours = (int) (totalETA / 3600);
        int minutes = (int) ((totalETA % 3600) / 60);
        int seconds = (int) (totalETA % 60);

        if (hours > 0) {
            return String.format("%dh %02dm %02ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    public static Config.ModpackContentFields getServerModpackContent(String link) {
        try {
            HttpRequest getContent = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(3))
                    .setHeader("Minecraft-Username", MinecraftUserName.get())
                    .setHeader("User-Agent", "github/skidamek/automodpack/" + AutoModpack.VERSION)
                    .uri(new URI(link))
                    .build();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpResponse<String> contentResponse = httpClient.send(getContent, HttpResponse.BodyHandlers.ofString());
            Config.ModpackContentFields serverModpackContent = GSON.fromJson(contentResponse.body(), Config.ModpackContentFields.class);

            if (serverModpackContent.list.size() < 1) {
                LOGGER.error("Modpack content is empty!");
                return null;
            }
            // check if modpackContent is valid/isn't malicious
            for (Config.ModpackContentFields.ModpackContentItems modpackContentItem : serverModpackContent.list) {
                String file = modpackContentItem.file.replace("\\", "/");
                String url = modpackContentItem.link.replace("\\", "/");
                if (file.contains("/../") || url.contains("/../")) {
                    LOGGER.error("Modpack content is invalid, it contains /../ in file name or url");
                    return null;
                }
            }

            return serverModpackContent;
        } catch (ConnectException e) {
            LOGGER.error("Couldn't connect to modpack server " + link);
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content");
            e.printStackTrace();
        }
        return null;
    }

    public ModpackUpdater(String link, File modpackDir, boolean loadIfItsNotLoaded) {
        if (link == null || link.isEmpty() || modpackDir.toString() == null || modpackDir.toString().isEmpty()) return;

        try {
            DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(MAX_DOWNLOADS);

            serverModpackContent = getServerModpackContent(link);

            if (serverModpackContent == null)  { // server is down, or you don't have access to internet, but we still want to load selected modpack

                if (!loadIfItsNotLoaded) return;

                File modpackContentFile = new File(modpackDir + File.separator + "modpack-content.json");

                if (!modpackContentFile.exists()) return;

                Config.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);
                if (modpackContent == null) return;

                if (!ModpackCheck.isLoaded(modpackContent)) {
                    LOGGER.info("Modpack is not loaded, loading...");
                    new ReLauncher.Restart(modpackDir);
                }

                return;
            }

            serverModpackContent.link = link;

            if (!modpackDir.exists()) modpackDir.mkdirs();

            File modpackContentFile = new File(modpackDir + File.separator + "modpack-content.json");

            if (modpackContentFile.exists()) {
                if (ModpackCheck.isUpdate(link, modpackDir) == ModpackCheck.UpdateType.NONE) {
                    // check if modpack is loaded now loaded
                    if (loadIfItsNotLoaded) {
                        if (!ModpackCheck.isLoaded(serverModpackContent)) {
                            LOGGER.info("Modpack is not loaded, loading...");
                            new ReLauncher.Restart(modpackDir);
                        }
                    }
                    return;
                }
            } else if (!AutoModpack.preload && ScreenTools.getScreen() != null) {
                if (serverModpackContent == null) return;
                CompletableFuture.runAsync(() -> {
                    while (!ScreenTools.getScreenString().contains("dangerscreen")) {
                        ScreenTools.setTo.Danger(ScreenTools.getScreen(), link, modpackDir, loadIfItsNotLoaded, modpackContentFile);
                        new Wait(100);
                    }
                });
                return;
            }

            if (serverModpackContent == null) return;

            ModpackUpdaterMain(link, modpackDir, loadIfItsNotLoaded, modpackContentFile);

        } catch (Exception e) {
            LOGGER.error("Error while initializing modpack updater");
            e.printStackTrace();
        }
    }

    public static void ModpackUpdaterMain(String link, File modpackDir, boolean loadIfItsNotLoaded, File modpackContentFile) {

        long start = System.currentTimeMillis();

        try {
            List<Config.ModpackContentFields.ModpackContentItems> copyModpackContentList = new ArrayList<>(serverModpackContent.list);

            for (Config.ModpackContentFields.ModpackContentItems modpackContentField : copyModpackContentList) {

                String fileName = modpackContentField.file;
                String serverChecksum = modpackContentField.hash;

                File fileInRunDir = new File("./" + fileName);

                if (!fileInRunDir.exists() || !fileInRunDir.isFile()) continue;

                if (serverChecksum.equals(CustomFileUtils.getHash(fileInRunDir, "SHA-256"))) {
                    LOGGER.info("Skipping already downloaded file: " + fileName);
                    totalBytesDownloaded += fileInRunDir.length();
                    copyModpackContentList.remove(modpackContentField);
                }
            }

            ModpackUpdater.wholeQueue = copyModpackContentList.size();

            LOGGER.info("In queue left " + wholeQueue + " files to download");

            if (wholeQueue > 0) {
                for (Config.ModpackContentFields.ModpackContentItems modpackContentField : copyModpackContentList) {
                    while (downloadFutures.size() >= MAX_DOWNLOADS) { // Async Setting - max `some` download at the same time
                        downloadFutures = downloadFutures.stream()
                                .filter(future -> !future.isDone())
                                .collect(Collectors.toList());
                    }

                    String fileName = modpackContentField.file;
                    String serverChecksum = modpackContentField.hash;
                    boolean isEditable = modpackContentField.isEditable;

                    File downloadFile = new File(modpackDir + File.separator + fileName);
                    String url;
                    if (modpackContentField.link.startsWith("/")) { // AutoModpack host
                        url = link + modpackContentField.link;
                        url = Url.encode(url); // We need to change things like [ ] to %5B %5D etc.
                    } else { // Other host
                        url = modpackContentField.link; // This link just must work, so we don't need to encode it
                    }

                    downloadFutures.add(processAsync(url, downloadFile, isEditable, serverChecksum));
                }

                CompletableFuture.allOf(downloadFutures.toArray(new CompletableFuture[0])).get();
            }

            // Downloads completed
            if (update) {
                // if was updated, delete old files from modpack
                List<String> files = serverModpackContent.list.stream().map(modpackContentField -> new File(modpackContentField.file).getName()).toList();

                try (Stream<Path> stream = Files.walk(modpackDir.toPath(), 10)) {
                    for (Path file : stream.toList()) {
                        if (Files.isDirectory(file)) continue;
                        if (file.equals(modpackContentFile.toPath())) continue;
                        if (!files.contains(file.toFile().getName())) {
                            LOGGER.info("Deleting " + file.toFile().getName());
                            CustomFileUtils.forceDelete(file.toFile(), true);
                            changelogList.put(file.toFile().getName(), false);
                        }
                    }
                } catch (IOException e) {
                    LOGGER.error("An error occurred while trying to walk through the files in the modpack directory", e);
                }
            }

            // Modpack updated
            // copy configs and other files to running directory
            for (Config.ModpackContentFields.ModpackContentItems mod : serverModpackContent.list) {
                if (mod.type.equals("config") || mod.type.equals("other")) {
                    File file = new File(modpackDir + File.separator + mod.file);
                    if (file.exists()) {
                        File fileInRunningDir = new File("." + mod.file);
                        if (fileInRunningDir.exists()) {
                            CustomFileUtils.forceDelete(fileInRunningDir, false);
                        }
                        CustomFileUtils.copyFile(file, fileInRunningDir);
                        AutoModpack.LOGGER.info("Copied " + mod.file + " to running directory");
                    }
                }
            }

            if (modpackContentFile.exists()) {
                CustomFileUtils.forceDelete(modpackContentFile, false);
            }

            Files.write(modpackContentFile.toPath(), GSON.toJson(serverModpackContent).getBytes());

            DeleteDuplicatedMods(new File(modpackDir + File.separator + "mods"));

            if (!failedDownloads.isEmpty()) {
                StringBuilder failedFiles = new StringBuilder("null");
                for (Map.Entry<String, String> entry : failedDownloads.entrySet()) {
                    LOGGER.error("Failed to download: " + entry.getKey() + " from " + entry.getValue());
                    failedFiles.append(entry.getKey());
                }
                ScreenTools.setTo.Error("Failed to download some files", "Failed to download: " + failedFiles, "More details in logs.");
                return;
            }

            LOGGER.info("Modpack is up-to-date! Took: " + (System.currentTimeMillis() - start) + " ms");

            if (loadIfItsNotLoaded) {
                if (!ModpackCheck.isLoaded(serverModpackContent)) {
                    LOGGER.info("Modpack is not loaded, loading...");
                    new ReLauncher.Restart(modpackDir);
                }
            }

            if (update) {
                new ReLauncher.Restart(modpackDir);
            }

        } catch (SocketTimeoutException | ConnectException e) {
            LOGGER.error("Modpack host of " + link + " is not responding", e);
        } catch (Exception e) {
            ScreenTools.setTo.Error("Critical error while downloading modpack.", "\"" + e.getMessage() + "\"", "More details in logs.");
            e.printStackTrace();
        }
    }

    private static CompletableFuture<Void> processAsync(String url, File downloadFile, boolean isEditable, String serverChecksum) {
        return CompletableFuture.runAsync(() -> process(url, downloadFile, isEditable, serverChecksum), DOWNLOAD_EXECUTOR);
    }

    private static void process(String url, File downloadFile, boolean isEditable, String serverChecksum) {
        if (!downloadFile.exists()) {
            downloadFile(url, downloadFile, serverChecksum);
            return;
        }

        if (isEditable) {
            LOGGER.info("File " + downloadFile.getName() + " is editable and already downloaded, skipping...");
            return;
        }

        try {
            String localChecksum = CustomFileUtils.getHash(downloadFile, "SHA-256");

            if (serverChecksum.equals(localChecksum)) { // up-to-date
                LOGGER.info("File " + downloadFile.getName() + " is up-to-date!");
                return;
            }

            LOGGER.warn(downloadFile.getName() + " Local checksum: " + localChecksum + " Server checksum: " + serverChecksum);
            downloadFile(url, downloadFile, serverChecksum);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void downloadFile(String url, File downloadFile, String serverChecksum) {
        if (!update || !ScreenTools.getScreenString().contains("downloadscreen")) {
            ScreenTools.setTo.Download();
        }
        update = true;
        DownloadInfo downloadInfo = new DownloadInfo(downloadFile.getName());
        downloadInfos.add(downloadInfo);

        int maxAttempts = 5;
        int attempts = 0;
        boolean success = false;
        long startTime = System.currentTimeMillis();

        while (attempts < maxAttempts && !success) {
            attempts++;
            AutoModpack.LOGGER.info("Downloading {}... (attempt {})", downloadFile.getName(), attempts);
            AutoModpack.LOGGER.info("URL: {}", url);

            try {
                Download downloadInstance = new Download();
                CompletableFuture.runAsync(() -> {
                    while (!downloadInstance.isDownloading()) {
                        new Wait(1);
                    }

                    long oldValue = 0;

                    while (downloadInstance.isDownloading()) {
                        oldValue = updateDownloadInfo(downloadInstance, downloadInfo, oldValue);
                        new Wait(25);
                    }
                });

                downloadInstance.download(url, downloadFile);

                String ourChecksum = CustomFileUtils.getHash(downloadFile, "SHA-256");

                long size = downloadInstance.getFileSize();

                if (serverChecksum.equals(ourChecksum)) {
                    success = true;
                } else if (attempts == maxAttempts && !downloadFile.toString().endsWith(".jar") && downloadFile.length() == size) {
                    // FIXME it shouldn't even return wrong checksum if the size is correct...
                    AutoModpack.LOGGER.warn("Checksums of {} do not match, but size is correct so we will assume it is correct lol", downloadFile.getName());
                    success = true;
                } else {
                    if (attempts != maxAttempts) {
                        AutoModpack.LOGGER.warn("Checksums do not match, retrying... client: {} server: {}", ourChecksum, serverChecksum);
                    }
                    CustomFileUtils.forceDelete(downloadFile, false);
                    totalBytesDownloaded -= size;
                }
            } catch (SocketTimeoutException e) {
                AutoModpack.LOGGER.error("Download of {} timed out, retrying...", downloadFile.getName());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        downloadInfos.remove(downloadInfo);
        if (success) {
            AutoModpack.LOGGER.info("{} downloaded successfully in {}ms", downloadFile.getName(), (System.currentTimeMillis() - startTime));
            changelogList.put(downloadFile.getName(), true);
            alreadyDownloaded++;
        } else {
            failedDownloads.put(downloadFile.getName(), url);
            AutoModpack.LOGGER.error("Failed to download {} after {} attempts", downloadFile.getName(), attempts);
        }
    }

    private static long updateDownloadInfo(Download downloadInstance, DownloadInfo downloadInfo, long oldValue) {
        downloadInfo.setBytesDownloaded(downloadInstance.getTotalBytesRead());
        downloadInfo.setDownloadSpeed(downloadInstance.getBytesPerSecond() / 1024 / 1024);
        downloadInfo.setDownloading(downloadInstance.isDownloading());
        downloadInfo.setCancelled(false);
        downloadInfo.setEta(downloadInstance.getETA());
        downloadInfo.setFileSize(downloadInstance.getFileSize());
        downloadInfo.setBytesPerSecond(downloadInstance.getBytesPerSecond());

        totalBytesDownloaded += downloadInstance.getTotalBytesRead() - oldValue;
        oldValue = downloadInstance.getTotalBytesRead();
        return oldValue;
    }


    // This method cancels the current download by interrupting the thread pool
    public static void cancelDownload() { // TODO fix issue that after this operation, you can't download anything again
        try {
            LOGGER.info("Cancelling download for " + downloadFutures.size() + " files...");
            downloadFutures.forEach(future -> future.cancel(true));
            DOWNLOAD_EXECUTOR.shutdownNow();

            downloadFutures.clear();
            downloadInfos.clear();
            DOWNLOAD_EXECUTOR = null;
            totalBytesDownloaded = 0;
            alreadyDownloaded = 0;
            failedDownloads.clear();
            changelogList.clear();
            update = false;

            LOGGER.info("Download canceled");

            if (ScreenTools.getScreenString().contains("download")) {
                ScreenTools.setTo.Title();
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

    private static void DeleteDuplicatedMods(File modpackModsFile) throws IOException {
        Map<String, String> defaultModNames = getDefaultMods();
        File[] modpackMods = modpackModsFile.listFiles();
        if (defaultModNames == null || modpackMods == null) return;

        for (File modpackMod : modpackMods) {
            String modId = JarUtilities.getModIdFromJar(modpackMod, true);
            if (defaultModNames.containsKey(modpackMod.getName()) || (modId != null && defaultModNames.containsValue(modId))) {
                LOGGER.info("Deleting duplicated mod: " + modpackMod.getName());
                File deletedModsFolder = new File( automodpackDir + "/deletedMods/");
                if (!deletedModsFolder.exists()) deletedModsFolder.mkdirs();
                CustomFileUtils.copyFile(modpackMod, new File(deletedModsFolder + File.separator + modpackMod.getName()));
                CustomFileUtils.forceDelete(modpackMod, true);
            }
        }
    }

    private static Map<String, String> getDefaultMods() {
        Map<String, String> defaultMods = new HashMap<>();
        File defaultModsFolder = new File("./mods/");
        File[] defaultModsFiles = defaultModsFolder.listFiles();
        if (defaultModsFiles == null) return null;
        for (File defaultMod : defaultModsFiles) {
            defaultMods.put(defaultMod.getName(), JarUtilities.getModIdFromJar(defaultMod, true));
        }
        return defaultMods;
    }
}