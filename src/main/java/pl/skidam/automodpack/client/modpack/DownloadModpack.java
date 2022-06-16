package pl.skidam.automodpack.client.modpack;

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
        public prepare() {
            LOGGER.error("0");

            if (AutoModpackConfig.danger_screen) {
                LOGGER.error("1");
                while (true) {
                    assert MinecraftClient.getInstance().currentScreen != null;
                    if (MinecraftClient.getInstance().currentScreen.toString().toLowerCase().contains("title") || MinecraftClient.getInstance().currentScreen.toString().toLowerCase().contains("multi") || MinecraftClient.getInstance().currentScreen.toString().toLowerCase().contains("options")) {
                        LOGGER.error("2");
                        break;
                    }

                    if (isOnServer) {
                        LOGGER.error("3");
                        MinecraftClient.getInstance().execute(() -> {
                            assert MinecraftClient.getInstance().world != null;
                            MinecraftClient.getInstance().world.disconnect();
                        });
                        isOnServer = false;
                        while (true) {
                            if (MinecraftClient.getInstance().currentScreen.toString().toLowerCase().contains("disconnected")) {
                                LOGGER.error("4");
                                break;
                            }
                        }
                        break;
                    }
                }

                LOGGER.error("4.5");
                MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new DangerScreen()));
                return;
            }

            LOGGER.error("5");
            new DownloadModpack();
        }
    }
}
