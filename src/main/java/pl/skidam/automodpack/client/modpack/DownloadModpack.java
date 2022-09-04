package pl.skidam.automodpack.client.modpack;

import net.minecraft.client.MinecraftClient;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.client.ui.DangerScreen;
import pl.skidam.automodpack.client.ui.LoadingScreen;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.server.HostModpack;
import pl.skidam.automodpack.ui.ScreenBox;
import pl.skidam.automodpack.utils.*;
import pl.skidam.automodpack.utils.Error;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackClient.isOnServer;
import static pl.skidam.automodpack.AutoModpackMain.*;

public class DownloadModpack {

    public static boolean preload;
    public static int maxInputs;
    public static int minInputs;

    public DownloadModpack() {

        if (CheckModpack.update && link.endsWith("/modpack") && link.startsWith("http://")) {
            LOGGER.info("Updating modpack");

            String baseLink = link.substring(0, link.lastIndexOf("/"));
            String contentLink = baseLink + "/content";

            if (Download.Download(contentLink, HostModpack.MODPACK_CONTENT_FILE.toFile())) {
                LOGGER.info("Failed to download content file!");
                new Error();
                return;
            }

            LOGGER.info("Successfully downloaded content file!");

            File updateDir = new File("./AutoModpack/updatedir/modpack/");

            if (updateDir.exists()) {
                FileUtils.deleteQuietly(updateDir);
            } else {
                updateDir.mkdirs();
            }

            // unzip current modpack zip
            try {
                new UnZipper(out, updateDir, "none");
            } catch (Exception e) {
                new Error();
                e.printStackTrace();
                return;
            }

            // For Loading Screen visuals
            try {
                Scanner serverContentList = new Scanner(HostModpack.MODPACK_CONTENT_FILE.toFile());
                List<String> clientContentList = GenerateContentList.generateContentList(out);

                while (serverContentList.hasNextLine()) {
                    String serverLine = serverContentList.nextLine();
                    if (!clientContentList.toString().contains(serverLine)) {
                        maxInputs++;
                    }
                }

                serverContentList.close();
            } catch (Exception e) {
                new Error();
                e.printStackTrace();
                return;
            }

            // Main logic
            try {
                Scanner serverContentList = new Scanner(HostModpack.MODPACK_CONTENT_FILE.toFile());
                List<String> clientContentList = GenerateContentList.generateContentList(out);

                while (serverContentList.hasNextLine()) {

                    String serverLine = serverContentList.nextLine();
                    String serverLineOfName = serverLine.substring(0, serverLine.indexOf(" |=<|+|>=| "));
                    long serverLineOfSize = Long.parseLong(serverLine.substring(serverLine.indexOf(" |=<|+|>=| ")+11));

                    String updateLink = baseLink + "/" + serverLineOfName;

                    if (updateLink.contains(" ")) {
                        updateLink = updateLink.replaceAll(" ", "%20");
                    }

                    File file = new File(updateDir + File.separator + serverLineOfName);

                    if (!serverLineOfName.endsWith("/")) {
                        File dirToCreate = new File(file.toString().replace(file.getName(), ""));
                        if (!dirToCreate.exists()) {
                            dirToCreate.mkdirs();
                        }
                    }

                    if (serverLineOfName.endsWith("/")) {
                        if (!file.exists()) {
                            file.mkdirs();
                        }
                    }

                    else if (clientContentList.toString().contains(serverLineOfName)) { // This starts with else from previous if to don't download empty folders. (Files which ends with "/")
                        if (file.length() != serverLineOfSize) {
                            minInputs++; // For Loading Screen visuals
                            LOGGER.warn("File {} doesn't match the size, (server) {} != {} (client), downloading from {}", file.getName(),serverLineOfSize, file.length(), updateLink);
                            if (Download.Download(updateLink, file)) {
                                LOGGER.error("Failed to download {} from {}!", file.getName(), updateLink);
                                return;
                            }
                            LOGGER.info("Successfully downloaded {}", file.getName());
                        }
                    }

                    else {
                        minInputs++; // For Loading Screen visuals
                        LOGGER.warn("File {} doesn't exists, downloading from {}!", file.getName(), updateLink);
                        if (Download.Download(updateLink, file)) {
                            LOGGER.error("Failed to download {} from {}!", file.getName(), updateLink);
                            return;
                        }
                        LOGGER.info("Successfully downloaded {}", file.getName());
                    }
                }

                serverContentList.close();
                minInputs++; // For Loading Screen visuals

                LOGGER.info("Successfully updated modpack!");

            } catch (Exception e) {
                new Error();
                e.printStackTrace();
                return;
            }

            // scan delmods txt to delete mods from updatedir
            try {
                FileReader fr = new FileReader(updateDir + File.separator + "delmods.txt");
                Scanner inFile = new Scanner(fr);

                while (inFile.hasNextLine()) {
                    String modName = inFile.nextLine();
                    File modFile = new File(updateDir + File.separator + "mods" + File.separator + modName);

                    if (modFile.exists()) {
                        FileDeleteStrategy.FORCE.delete(modFile);
                    }
                }

                inFile.close();
                fr.close();
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                new Zipper(updateDir, out);
            } catch (Exception e) {
                e.printStackTrace();
            }

            FileUtils.deleteQuietly(updateDir);

        } else {
            LOGGER.info("Downloading modpack from {}...", link);

            // Download and check if download is successful *magic*

            if (Download.Download(link, out)) {
                LOGGER.info("Failed to download modpack!");
                return;
            }

            LOGGER.info("Successfully downloaded modpack!");

        }

        new UnZip(out, "true");

        if (!modsPath.getFileName().toString().equals("mods")) {
            try {
                FileUtils.moveDirectory(new File("./mods/"), new File(modsPath.toFile() + File.separator));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        CheckModpack.update = false;
        maxInputs = 0;
        minInputs = 0;

        if (preload) {
            new ScreenBox("Updated modpack, restart your game!");
        }

//        try {
//            new Relaunch();
//        } catch (Throwable e) {
//            LOGGER.error("Failed to relaunch minecraft! " + e);
//            e.printStackTrace();
//            new ScreenBox("Updated modpack, restart your game!");
//        }

    }

    public static class prepare {

        public static boolean DangerScreenWasShown = false;

        public prepare(boolean preload) {

            DownloadModpack.preload = preload;

            if (preload) {
                new DownloadModpack();
                return;
            }

            while (true) {
                new Wait(250);
                if (MinecraftClient.getInstance().currentScreen != null) {
                    if (!isOnServer) {
                        DangerScreenWasShown = false;
                        break;
                    }
                }

                if (isOnServer) {
                    if (MinecraftClient.getInstance().world != null) {
                        MinecraftClient.getInstance().world.disconnect();
                    }

                    if (MinecraftClient.getInstance().currentScreen != null) {
                        if (MinecraftClient.getInstance().currentScreen.toString().toLowerCase().contains("disconnected") || MinecraftClient.getInstance().currentScreen.toString().toLowerCase().contains("419")) {
                            DangerScreenWasShown = false;
                            isOnServer = false;
                            break;
                        }
                    }
                }
            }

            if (isVelocity) {
                while (true) {
                    if (MinecraftClient.getInstance().currentScreen != null) {
                        if (MinecraftClient.getInstance().currentScreen.toString().toLowerCase().contains("disconnected") || MinecraftClient.getInstance().currentScreen.toString().toLowerCase().contains("419")) {
                            break;
                        }
                    }
                }
            }

            if (Config.DANGER_SCREEN) {
                MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new DangerScreen()));
            }
            if (!Config.DANGER_SCREEN) {
                CompletableFuture.runAsync(DownloadModpack::new);
                MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new LoadingScreen()));
            }
        }
    }
}