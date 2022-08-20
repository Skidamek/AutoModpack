package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.client.ui.RestartScreen;
import pl.skidam.automodpack.utils.Wait;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

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
                    MinecraftClient.getInstance().world.disconnect();
                }

                if (MinecraftClient.getInstance().currentScreen != null) {
                    if (currentScreen.contains("419")) {
                        isOnServer = false;
                        break;
                    }
                }
            } else {
                if (MinecraftClient.getInstance().world != null) {
                    break;
                }
            }
        }

        Text bothUpdates = Text.translatable("gui.automodpack.screen.restart.title.all").formatted(Formatting.BOLD);
        Text modpackUpdate = Text.translatable("gui.automodpack.screen.restart.title.modpack").formatted(Formatting.BOLD);
        Text automodpackUpdate = Text.translatable("gui.automodpack.screen.restart.title.automodpack").formatted(Formatting.BOLD);

        LOGGER.info("Here you are!");

        if (AutoModpackUpdated.equals("true") && ModpackUpdated.equals("true")) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new RestartScreen(bothUpdates)));
        }

        if (AutoModpackUpdated.equals("true") && ModpackUpdated.equals("false")) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new RestartScreen(automodpackUpdate)));
        }

        if (AutoModpackUpdated.equals("false") && ModpackUpdated.equals("true")) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new RestartScreen(modpackUpdate)));
        }

        AutoModpackUpdated = null;
        ModpackUpdated = null;
        StartAndCheck.isChecking = false;

        CompletableFuture.runAsync(() -> {
            new Wait(500);
            if (Objects.requireNonNull(MinecraftClient.getInstance().currentScreen).toString().toLowerCase().contains("loading")) {
                MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new TitleScreen()));
            }
        });
    }
}