package pl.skidam.automodpack.client.modpack;

import net.minecraft.client.MinecraftClient;

import pl.skidam.automodpack.client.ui.DangerScreen;
import pl.skidam.automodpack.config.AutoModpackConfig;
import pl.skidam.automodpack.utils.Download;

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

            if (AutoModpackConfig.danger_screen) {
                while (true) {
                    try {
                        String screen = MinecraftClient.getInstance().currentScreen.toString().toLowerCase();
                        String hud = MinecraftClient.getInstance().inGameHud.toString().toLowerCase();
                        LOGGER.warn(hud);
                        if (!hud.toLowerCase().contains("ingamehud") || !screen.contains("connect") && !screen.contains("download") && !screen.contains("progress")) {
                            break;
                        }
                    } catch (Exception e) { // ignore
                    }

                }
                MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new DangerScreen()));
                return;
            }

            new DownloadModpack();
        }
    }
}
