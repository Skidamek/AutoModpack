package pl.skidam.automodpack.client;

import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.Download;
import pl.skidam.automodpack.Platform;
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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(MAX_DOWNLOADS);
    public static boolean update;
    public static long totalBytesDownloaded = 0;
    private static int alreadyDownloaded = 0;
    private static int wholeQueue = 0;
    private static Config.ModpackContentFields serverModpackContent;
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
                    .setHeader("X-Minecraft-Username", MinecraftUserName.get())
                    .uri(new URI(link))
                    .build();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpResponse<String> contentResponse = httpClient.send(getContent, HttpResponse.BodyHandlers.ofString());
            return GSON.fromJson(contentResponse.body(), Config.ModpackContentFields.class);
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
            Collection modList = Platform.getModList();
            HashMap<String, Integer> localMods = new HashMap<>();
            int i = 0;
            for (Object mod : modList) {
                i++;
                String modId = mod.toString().split(" ")[0]; // mod is  "modid (version)" so we remove everything after space to get modid (modid can't have space in it)
                localMods.put(modId, i);
            }

            wholeQueue = serverModpackContent.list.size();
            for (Config.ModpackContentFields.ModpackContentItems modpackContentField : serverModpackContent.list) {
                while (downloadFutures.size() >= MAX_DOWNLOADS) { // Async Setting - max `some` download at the same time
                    downloadFutures = downloadFutures.stream()
                            .filter(future -> !future.isDone())
                            .collect(Collectors.toList());
                }

                String fileName = modpackContentField.file;
                LOGGER.info("Downloading file: " + fileName);

                String serverModId = modpackContentField.modId;
                String serverChecksum = modpackContentField.hash;
                boolean isMod = modpackContentField.type.equals("mod");
                boolean isEditable = modpackContentField.isEditable;

                File downloadFile = new File(modpackDir + File.separator + fileName);
                String url;
                if (modpackContentField.link.startsWith("/")) { // AutoModpack host
                    url = link + modpackContentField.link;
                    url = Url.encode(url); // We need to change things like [ ] to %5B %5D etc.
                } else { // Other host
                    url = modpackContentField.link; // This link just must work, so we don't need to encode it
                }

                CompletableFuture<Void> downloadFuture;
                if (isMod && !localMods.containsKey(serverModId)) {
                    downloadFuture = downloadAsync(url, downloadFile, serverChecksum);
                } else {
                    downloadFuture = processAsync(url, downloadFile, isEditable, serverChecksum);
                }

                downloadFutures.add(downloadFuture);
            }

            CompletableFuture.allOf(downloadFutures.toArray(new CompletableFuture[0])).get();

            if (update) {
                // if was updated, delete old files from modpack
                List<String> files = serverModpackContent.list.stream().map(modpackContentField -> new File(modpackContentField.file).getName()).toList();

                try (Stream<Path> stream = Files.walk(modpackDir.toPath(), 10)) {
                    for (Path file : stream.toList()) {
                        if (Files.isDirectory(file)) continue;
                        if (file.equals(modpackContentFile.toPath())) continue;
                        if (!files.contains(file.toFile().getName())) {
                            LOGGER.info("Deleting " + file.toFile().getName());
                            CustomFileUtils.forceDelete(file.toFile());
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
                            CustomFileUtils.forceDelete(fileInRunningDir);
                        }
                        CustomFileUtils.copyFile(file, fileInRunningDir);
                        AutoModpack.LOGGER.info("Copied " + mod.file + " to running directory");
                    }
                }
            }

            if (modpackContentFile.exists()) {
                CustomFileUtils.forceDelete(modpackContentFile);
            }

            Files.write(modpackContentFile.toPath(), GSON.toJson(serverModpackContent).getBytes());

            DeleteDuplicatedMods(new File(modpackDir + File.separator + "mods"));

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

    private static CompletableFuture<Void> downloadAsync(String url, File downloadFile, String serverChecksum) {
        return CompletableFuture.runAsync(() -> download(url, downloadFile, serverChecksum), DOWNLOAD_EXECUTOR);
    }

    private static CompletableFuture<Void> processAsync(String url, File downloadFile, boolean isEditable, String serverChecksum) {
        return CompletableFuture.runAsync(() -> process(url, downloadFile, isEditable, serverChecksum), DOWNLOAD_EXECUTOR);
    }

    private static void process(String url, File downloadFile, boolean isEditable, String serverChecksum) {
        if (!downloadFile.exists()) {
            download(url, downloadFile, serverChecksum);
            return;
        }

        if (isEditable) {
            LOGGER.info("File " + downloadFile.getName() + " is editable and already downloaded, skipping...");
            return;
        }

        try {
            String localChecksum = CustomFileUtils.getSHA512(downloadFile);

            if (serverChecksum.equals(localChecksum)) { // up-to-date
                LOGGER.info("File " + downloadFile.getName() + " is up-to-date!");
                return;
            } else {
                LOGGER.warn(downloadFile.getName() + " Local checksum: " + localChecksum + " Server checksum: " + serverChecksum);
            }

            download(url, downloadFile, serverChecksum);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void download(String url, File downloadFile, String serverChecksum) {
        // call it only once when update is false yet
        if (!update || !ScreenTools.getScreenString().contains("downloadscreen")) ScreenTools.setTo.Download();
        update = true;

        DownloadInfo downloadInfo = new DownloadInfo(downloadFile.getName());

        int tries = 0;
        while (tries < 5) {
            tries++;
            try {
                long start = System.currentTimeMillis();
                AutoModpack.LOGGER.info("Downloading {}... (try {})", downloadFile.getName(), tries);
                AutoModpack.LOGGER.info("URL: {}", url);
                if (!downloadInfos.contains(downloadInfo)) downloadInfos.add(downloadInfo);
                Download downloadInstance = new Download();


                Future<Void> future = CompletableFuture.runAsync(() -> {
                    while (!downloadInstance.isDownloading()) {
                        new Wait(1);
                    }

                    long oldValue = 0;

                    while (downloadInstance.isDownloading()) {
                        downloadInfo.setBytesDownloaded(downloadInstance.getTotalBytesRead());
                        downloadInfo.setDownloadSpeed(downloadInstance.getBytesPerSecond() / 1024 / 1024);
                        downloadInfo.setDownloading(downloadInstance.isDownloading());
                        downloadInfo.setCancelled(false);
                        downloadInfo.setEta(downloadInstance.getETA());
                        downloadInfo.setFileSize(downloadInstance.getFileSize());
                        downloadInfo.setBytesPerSecond(downloadInstance.getBytesPerSecond());

                        totalBytesDownloaded += downloadInstance.getTotalBytesRead() - oldValue;
                        oldValue = downloadInstance.getTotalBytesRead();

                        new Wait(25);
                    }

                    totalBytesDownloaded += downloadInstance.getTotalBytesRead() - oldValue;
                });

                long fileSize = WebFileSize.getWebFileSize(url);

                String ourChecksum = downloadInstance.download(url, downloadFile);

                if (!future.isDone()) future.cancel(true);

                if (serverChecksum.equals(ourChecksum)) {
                    AutoModpack.LOGGER.info("{} downloaded successfully in {}ms", downloadFile.getName(), (System.currentTimeMillis() - start));
                    changelogList.put(downloadFile.getName(), true);
                    alreadyDownloaded++;
                    downloadInfos.remove(downloadInfo);
                    return;
                } else {
                    if (tries == 5) {
                        if (fileSize == downloadFile.length()) { // TODO fix issue that some files returns wrong checksums, and delete this `if`
                            AutoModpack.LOGGER.info("{} downloaded `kinda` successfully in {}ms", downloadFile.getName(), (System.currentTimeMillis() - start));
                            changelogList.put(downloadFile.getName(), true);
                            alreadyDownloaded++;
                            downloadInfos.remove(downloadInfo);
                            return;
                        }
                    }
                    AutoModpack.LOGGER.warn("Checksums don't match, try again, client: {} server: {}", ourChecksum, serverChecksum);
                    Files.deleteIfExists(downloadFile.toPath());
                }
            } catch (SocketTimeoutException e) {
                // Download loop
                AutoModpack.LOGGER.error("Download of {} got timeout, trying again...", downloadFile.getName());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        downloadInfos.remove(downloadInfo);
        AutoModpack.LOGGER.error("Failed to download {} after {} tries", downloadFile.getName(), tries);
    }


    // This method cancels the current download by interrupting the thread pool
    public static void cancelDownload() { // TODO fix issue that after this operation, you can't download anything again
        LOGGER.info("Cancelling download for " + downloadFutures.size() + " files...");
        downloadFutures.forEach(future -> future.cancel(true));
        DOWNLOAD_EXECUTOR.shutdownNow();
        LOGGER.info("Download canceled");

        if (ScreenTools.getScreenString().contains("download")) {
            ScreenTools.setTo.Title();
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
        File defaultModsFolder = new File("./mods/");
        File[] defaultMods = defaultModsFolder.listFiles();
        File[] modpackMods = modpackModsFile.listFiles();
        if (defaultMods == null || modpackMods == null) return;
        Map<String, String> modpackModNames = new HashMap<>();
        for (File modpackMod : modpackMods) {
            modpackModNames.put(modpackMod.getName(), JarUtilities.getModIdFromJar(modpackMod, true));
        }
        for (File defaultMod : defaultMods) {
            if (modpackModNames.containsKey(defaultMod.getName()) || modpackModNames.containsValue(JarUtilities.getModIdFromJar(defaultMod, true))) {
                LOGGER.info("Deleting duplicated mod: " + defaultMod.getName());
                File deletedModsFolder = new File( automodpackDir + "/deletedMods/");
                if (!deletedModsFolder.exists()) deletedModsFolder.mkdirs();
                Files.copy(defaultMod.toPath(), new File(deletedModsFolder + File.separator + defaultMod.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                CustomFileUtils.forceDelete(defaultMod);
            }
        }
    }
}