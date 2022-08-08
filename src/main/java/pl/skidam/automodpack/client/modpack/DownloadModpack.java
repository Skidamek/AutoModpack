package pl.skidam.automodpack.client.modpack;

import net.minecraft.client.MinecraftClient;

import pl.skidam.automodpack.client.ui.DangerScreen;
import pl.skidam.automodpack.client.ui.LoadingScreen;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.Relaunch;
import pl.skidam.automodpack.utils.Download;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackClient.isOnServer;
import static pl.skidam.automodpack.AutoModpackMain.*;

public class DownloadModpack {

    private static boolean preload;

    public DownloadModpack() {

        LOGGER.info("Downloading modpack form {}...", link);

        // Download and check if download is successful *magic*

        if (Download.Download(link, out)) {
            LOGGER.info("Failed to download modpack!");
            return;
        }

        LOGGER.info("Successfully downloaded modpack!");

        new UnZip(out, "true");

        if (preload) {
            try {
                new Relaunch();
            } catch (Throwable e) {
                LOGGER.error("Failed to relaunch minecraft! " + e);
                e.printStackTrace();
                throw new RuntimeException("Updated modpack, restart your game!");
            }
        }
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

                    if (MinecraftClient.getInstance().currentScreen.toString().toLowerCase().contains("419")) {
                        DangerScreenWasShown = false;
                        isOnServer = false;
                        break;
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