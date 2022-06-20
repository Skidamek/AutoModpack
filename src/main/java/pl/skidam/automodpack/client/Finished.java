package pl.skidam.automodpack.client;

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

        LOGGER.error("Finished!");
        while (true) {
            if (DangerScreenWasShown) {
                DangerScreenWasShown = false;
                break;
            }

            assert MinecraftClient.getInstance().currentScreen != null;
            String currentScreen = MinecraftClient.getInstance().currentScreen.toString().toLowerCase();
            // dev env
//                    if (currentScreen.contains("title") || currentScreen.contains("multi") || currentScreen.contains("options" || currentScreen.contains("modsscreen")) {
//                        break;
//                    }
            // prod env
            if (currentScreen.contains("442") || currentScreen.contains("500") || currentScreen.contains("429") || currentScreen.contains("526") || currentScreen.contains("525") || currentScreen.contains("424") || currentScreen.contains("modsscreen") || currentScreen.contains("loading")) {
                break;
            }

            if (ModpackUpdated.equals("true")) {
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

        if (MinecraftClient.getInstance().currentScreen.toString().contains("loading")) {
            MinecraftClient.getInstance().setScreen(new TitleScreen());
        }
    }
}