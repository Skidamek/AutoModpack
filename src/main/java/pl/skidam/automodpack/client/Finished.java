package pl.skidam.automodpack.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.client.ui.ConfirmScreen;

import static pl.skidam.automodpack.AutoModpackClient.isOnServer;
import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.client.modpack.DownloadModpack.prepare.DangerScreenWasShown;

public class Finished {

    public Finished() {

        while (true) {
            if (DangerScreenWasShown) {
                DangerScreenWasShown = false;
                break;
            }

            assert MinecraftClient.getInstance().currentScreen != null;
            String currentScreen = MinecraftClient.getInstance().currentScreen.toString().toLowerCase();
            LOGGER.error(currentScreen);
            // dev env
//                    if (currentScreen.contains("title") || currentScreen.contains("multi") || currentScreen.contains("options" || currentScreen.contains("modsscreen")) {
//                        break;
//                    }
            // prod env
            if (currentScreen.contains("442") || currentScreen.contains("500") || currentScreen.contains("429") || currentScreen.contains("526") || currentScreen.contains("525") || currentScreen.contains("424") || currentScreen.contains("modsscreen") || currentScreen.contains("loading")) {
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


        Text bothUpdates = Text.translatable("gui.automodpack.screen.confirm.title.all").formatted(Formatting.BOLD);
        Text modpackUpdate = Text.translatable("gui.automodpack.screen.confirm.title.modpack").formatted(Formatting.BOLD);
        Text automodpackUpdate = Text.translatable("gui.automodpack.screen.confirm.title.automodpack").formatted(Formatting.BOLD);

        LOGGER.info("Here you are!");

        if (AutoModpackUpdated.equals("true") && ModpackUpdated.equals("true")) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new ConfirmScreen(bothUpdates)));
        }

        if (AutoModpackUpdated.equals("true") && ModpackUpdated.equals("false")) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new ConfirmScreen(automodpackUpdate)));
        }

        if (AutoModpackUpdated.equals("false") && ModpackUpdated.equals("true")) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new ConfirmScreen(modpackUpdate)));
        }

        AutoModpackUpdated = null;
        ModpackUpdated = null;
        Checking = false;

        if (MinecraftClient.getInstance().currentScreen.toString().contains("loading")) {
            MinecraftClient.getInstance().setScreen(new TitleScreen());
        }
    }
}