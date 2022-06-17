package pl.skidam.automodpack.client.modpack;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;

import pl.skidam.automodpack.client.ui.DangerScreen;
import pl.skidam.automodpack.config.AutoModpackConfig;
import pl.skidam.automodpack.utils.Download;

import static pl.skidam.automodpack.AutoModpackClient.isOnServer;
import static pl.skidam.automodpack.AutoModpackMain.*;

public class DownloadModpack {

    public DownloadModpack() {

        LOGGER.info("Downloading modpack form {}...", link);

        // Download and check if download is successful *magic*

        if (Download.Download(link, out)) {
            LOGGER.info("Failed downloaded modpack!");
            return;
        }

        LOGGER.info("Successfully downloaded modpack!");

        new UnZip(out, "true");
    }

    public static class prepare {

        public static boolean DangerScreenWasShown = false;

        public prepare() {

            if (!AutoModpackConfig.danger_screen) {
                new DownloadModpack();
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

                if (isOnServer) {
                    while (true) {
                        if (ClientPlayNetworking.canSend(AM_KICK)) {
                            // TODO change it to ClientLoginNetwork
                            ClientPlayNetworking.send(AM_KICK, PacketByteBufs.empty());
                            LOGGER.error("sent kick packet 1");
                            break;
                        }
                        LOGGER.error("sent kick packet 2");
                    }
                    LOGGER.error("sent kick packet 3");
                    while (true) {
                        LOGGER.error("Waiting for server to kick...");
                        assert MinecraftClient.getInstance().currentScreen != null;
                        String currentScreenGO = MinecraftClient.getInstance().currentScreen.toString().toLowerCase();
                        // dev env
//                            if (currentScreen.contains("disconnected")) {
//                                break;
//                            }
                        // prod env
                        if (currentScreenGO.contains("419")) {
                            LOGGER.error("Server kicked us!");
                            isOnServer = false;
                            break;
                        }
                    }
                    break;
                }
            }

            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new DangerScreen()));
        }
    }
}
