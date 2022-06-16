package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.client.ui.ConfirmScreen;

import static pl.skidam.automodpack.AutoModpackClient.isOnServer;
import static pl.skidam.automodpack.AutoModpackMain.*;

public class Finished {

    public Finished() {

        new Thread(() -> {
            // If minecraft is still loading wait for it to finish
            while (true) {
                try {
                    String screen = MinecraftClient.getInstance().currentScreen.toString().toLowerCase();
                    String hud = MinecraftClient.getInstance().inGameHud.toString().toLowerCase();
                    if (isOnServer || !hud.toLowerCase().contains("ingamehud") || !screen.contains("connect") && !screen.contains("download") && !screen.contains("progress")) {
                        if (isOnServer) {
                            isOnServer = false;
                        }
                        break;
                    }
                } catch (Exception e) { // ignore
                }
            }
        }).start();

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
    }
}