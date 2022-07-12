package pl.skidam.automodpack.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import pl.skidam.automodpack.client.AutoModpackToast;

import java.util.Objects;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.client.StartAndCheck.isChecking;

public class Error {

    public Error() {

        AutoModpackUpdated = "false";
        ModpackUpdated = "false";
        isChecking = false;

        AutoModpackToast.add(5);

        LOGGER.error("Error! Download server may be down, AutoModpack is wrongly configured or you just don't have internet connection!");
        LOGGER.error("Error! Download server may be down, AutoModpack is wrongly configured or you just don't have internet connection!");
        LOGGER.error("Error! Download server may be down, AutoModpack is wrongly configured or you just don't have internet connection!");

        if (Objects.requireNonNull(MinecraftClient.getInstance().currentScreen).toString().toLowerCase().contains("loading")) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new TitleScreen()));
        }
    }
}
