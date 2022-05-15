package pl.skidam.automodpack;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;

import static pl.skidam.automodpack.AutoModpack.AutoModpackUpdated;
import static pl.skidam.automodpack.AutoModpack.ModpackUpdated;

public class Finished {

    String AutoModpackUpdated;
    String ModpackUpdated;
    boolean Done;


    // TODO try make boolens public and use them there but call this class by AutoModpack.java


    public Finished(boolean Done, String AutoModpackUpdated, String ModpackUpdated) {

        this.AutoModpackUpdated = AutoModpackUpdated;
        this.ModpackUpdated = ModpackUpdated;
        this.Done = Done;

        Text bothUpdates = new TranslatableText("gui.automodpack.screen.title.all").formatted(Formatting.BOLD);
        Text modpackUpdate = new TranslatableText("gui.automodpack.screen.title.modpack").formatted(Formatting.BOLD);
        Text automodpackUpdate = new TranslatableText("gui.automodpack.screen.title.automodpack").formatted(Formatting.BOLD);

        if (Done) {
            Thread.currentThread().setPriority(10);

            AutoModpack.LOGGER.info("Here you are!");

//            new ToastExecutor(7);

        }

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

        AutoModpack.AutoModpackUpdated = null;
        AutoModpack.ModpackUpdated = null;
    }
}
