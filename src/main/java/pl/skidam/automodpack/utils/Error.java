package pl.skidam.automodpack.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.client.StartAndCheck.isChecking;

public class Error {

    public Error() {

        AutoModpackUpdated = "false";
        ModpackUpdated = "false";
        isChecking = false;

        new ToastExecutor(5);

        LOGGER.error("Error! Download server may be down, AutoModpack is wrongly configured or you just don't have internet connection!");
        LOGGER.error("Error! Download server may be down, AutoModpack is wrongly configured or you just don't have internet connection!");
        LOGGER.error("Error! Download server may be down, AutoModpack is wrongly configured or you just don't have internet connection!");

        try {
            if (MinecraftClient.getInstance().currentScreen.toString().contains("loading")) {
                MinecraftClient.getInstance().setScreen(new TitleScreen());
            }
        } catch (Exception e) {
        }
    }
}
