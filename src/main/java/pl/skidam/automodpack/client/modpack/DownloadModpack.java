package pl.skidam.automodpack.client.modpack;

import net.minecraft.client.MinecraftClient;

import pl.skidam.automodpack.client.ui.DangerScreen;
import pl.skidam.automodpack.client.ui.LoadingScreen;
import pl.skidam.automodpack.config.AutoModpackConfig;
import pl.skidam.automodpack.utils.Download;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackClient.isOnServer;
import static pl.skidam.automodpack.AutoModpackMain.*;

public class DownloadModpack {

    public DownloadModpack() {

        LOGGER.info("Downloading modpack form {}...", link);

        // Download and check if download is successful *magic*

        if (Download.Download(link, out)) {
            LOGGER.info("Failed to download modpack!");
            return;
        }

        LOGGER.info("Successfully downloaded modpack!");

        new UnZip(out, "true");
    }

    public static class prepare {

        public static boolean DangerScreenWasShown = false;

        public prepare() {

            if (!AutoModpackConfig.danger_screen) {
                if (MinecraftClient.getInstance().currentScreen != null) {
                    CompletableFuture.runAsync(DownloadModpack::new);
                    // check if player is joining server
                    if (isOnServer) {
                        while (true) {
                            if (MinecraftClient.getInstance().currentScreen.toString().toLowerCase().contains("419")) {
                                isOnServer = false;
                                break;
                            }

                            if (MinecraftClient.getInstance().world != null) {
                                MinecraftClient.getInstance().world.disconnect();
                            }
                        }
                     }

                    MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new LoadingScreen()));
                }

                return;
            }

            while (true) {
                assert MinecraftClient.getInstance().currentScreen != null;
                String currentScreen = MinecraftClient.getInstance().currentScreen.toString().toLowerCase();
                // dev env
//                    if (currentScreen.contains("title") || currentScreen.contains("multi") || currentScreen.contains("options" || currentScreen.contains("modsscreen")) {
//                        break;
//                    }
                // prod env
                if (currentScreen.contains("442") || currentScreen.contains("500") || currentScreen.contains("429") || currentScreen.contains("526") || currentScreen.contains("525") || currentScreen.contains("424") || currentScreen.contains("modsscreen")) {
                    DangerScreenWasShown = true;
                    break;
                }

                if (MinecraftClient.getInstance().world != null) {
                    MinecraftClient.getInstance().world.disconnect();
                }

                if (MinecraftClient.getInstance().currentScreen != null) {
                    // dev env
//                            if (currentScreen.contains("disconnected")) {
//                                break;
//                            }
                    // prod env
                    if (currentScreen.contains("419")) {
                        isOnServer = false;
                        break;
                    }

                }

            }

            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new DangerScreen()));
        }
    }
}
