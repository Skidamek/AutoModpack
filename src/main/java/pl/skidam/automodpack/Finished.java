package pl.skidam.automodpack;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.ui.ConfirmScreen;

import static pl.skidam.automodpack.AutoModpackClient.*;

public class Finished {

    public Finished() {

        Text bothUpdates = new TranslatableText("gui.automodpack.screen.title.all").formatted(Formatting.BOLD);
        Text modpackUpdate = new TranslatableText("gui.automodpack.screen.title.modpack").formatted(Formatting.BOLD);
        Text automodpackUpdate = new TranslatableText("gui.automodpack.screen.title.automodpack").formatted(Formatting.BOLD);

        Thread.currentThread().setPriority(10);

        AutoModpackClient.LOGGER.info("Here you are!");


        if (AutoModpackUpdated == "true" && ModpackUpdated == "true") {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().setScreen(new ConfirmScreen(bothUpdates));
            });
        }

        if (AutoModpackUpdated == "true" && ModpackUpdated == "false") {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().setScreen(new ConfirmScreen(automodpackUpdate));
            });
        }

        if (AutoModpackUpdated == "false" && ModpackUpdated == "true") {
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().setScreen(new ConfirmScreen(modpackUpdate));
            });
        }

        AutoModpackUpdated = null;
        ModpackUpdated = null;
        AutoModpackClient.Checking = false;
    }
}
