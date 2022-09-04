package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.client.ui.RestartScreen;
import pl.skidam.automodpack.utils.Wait;

import java.util.Objects;

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

            // Doesn't work well on dev env
            String currentScreen = Objects.requireNonNull(MinecraftClient.getInstance().currentScreen).toString().toLowerCase();
            if (currentScreen.contains("442") || currentScreen.contains("500") || currentScreen.contains("429") || currentScreen.contains("526") || currentScreen.contains("525") || currentScreen.contains("424") || currentScreen.contains("modsscreen") || currentScreen.contains("loading") || currentScreen.contains("title")) {
                break;
            }

            if (ModpackUpdated.equals("true") || AutoModpackUpdated.equals("true")) {
                if (MinecraftClient.getInstance().world != null) {
                    MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().world.disconnect());
                    new Wait(250);
                }

                if (MinecraftClient.getInstance().currentScreen != null) {
                    isOnServer = false;
                    break;
                }
            } else {
                if (MinecraftClient.getInstance().world != null) {
                    break;
                }
            }
            break;
        }

        Text bothUpdates = Text.translatable("gui.automodpack.screen.restart.title.all").formatted(Formatting.BOLD);
        Text modpackUpdate = Text.translatable("gui.automodpack.screen.restart.title.modpack").formatted(Formatting.BOLD);
        Text automodpackUpdate = Text.translatable("gui.automodpack.screen.restart.title.automodpack").formatted(Formatting.BOLD);

        LOGGER.info("Here you are!");

        if (AutoModpackUpdated.equals("true") && ModpackUpdated.equals("true")) {
            AutoModpackUpdated = null;
            ModpackUpdated = null;
            StartAndCheck.isChecking = false;
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new RestartScreen(bothUpdates)));
        }

        else if (AutoModpackUpdated.equals("true") && ModpackUpdated.equals("false")) {
            AutoModpackUpdated = null;
            ModpackUpdated = null;
            StartAndCheck.isChecking = false;
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new RestartScreen(automodpackUpdate)));
        }

        else if (AutoModpackUpdated.equals("false") && ModpackUpdated.equals("true")) {
            AutoModpackUpdated = null;
            ModpackUpdated = null;
            StartAndCheck.isChecking = false;
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new RestartScreen(modpackUpdate)));
        }

        else if (MinecraftClient.getInstance().currentScreen != null) {
            if (AutoModpackUpdated.equals("false") && ModpackUpdated.equals("false")) return;
            AutoModpackUpdated = null;
            ModpackUpdated = null;
            StartAndCheck.isChecking = false;
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new TitleScreen()));
        }
    }
}