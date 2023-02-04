package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import pl.skidam.automodpack.client.ui.*;
import pl.skidam.automodpack.utils.Checks;

import java.io.File;

public class ScreenTools {

    public static class setTo { // Save screen's. Don't worry that minecraft didn't load yet, or you will crash server by executing screen's methods

        // TODO Make it work, it is much better code but it doesnt resolve the issue which we want to resolve by this class
//        public setTo(Screen screen) {
//            if (Checks.properlyLoaded()) Screens.setScreen(screen);
//        }

        public static void Download() {
            if (Checks.properlyLoaded()) Screens.DownloadScreen();
        }

        public static void Restart(Screen parent, File gameDir) {
            if (Checks.properlyLoaded()) Screens.RestartScreen(parent, gameDir);
        }

        public static void Danger(Screen parent, String link, File modpackDir, boolean loadIfItsNotLoaded, File modpackContentFile) {
            if (Checks.properlyLoaded()) Screens.DangerScreen(parent, link, modpackDir, loadIfItsNotLoaded, modpackContentFile);
        }

        public static void Error(String... error) {
            if (Checks.properlyLoaded()) Screens.ErrorScreen(error);
        }

        public static void Title() {
            if (Checks.properlyLoaded()) Screens.TitleScreen();
        }
    }

    public static String getScreenString() {
        if (Checks.properlyLoaded()) {
            Screen screen = Screens.getScreen();
            return screen.getTitle().getString().toLowerCase();
        }
        return "null";
    }

    public static Screen getScreen() {
        if (Checks.properlyLoaded()) {
            return Screens.getScreen();
        }
        return null;
    }


    private static class Screens { // It has to be in a separate class, or it will crash
        static Screen getScreen() {
            return MinecraftClient.getInstance().currentScreen;
        }

        public static void setScreen(Screen screen) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(screen));
        }

        public static void DownloadScreen() {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new DownloadScreen()));
        }

        public static void RestartScreen(Screen parent, File gameDir) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new RestartScreen(parent, gameDir)));
        }

        public static void DangerScreen(Screen parent, String link, File modpackDir, boolean loadIfItsNotLoaded, File modpackContentFile) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new DangerScreen(parent, link, modpackDir, loadIfItsNotLoaded, modpackContentFile)));
        }

        public static void TitleScreen() {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new TitleScreen()));
        }

        public static void ErrorScreen(String... error) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new ErrorScreen(error)));
        }
    }
}
