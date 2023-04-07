package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.client.ui.*;

import java.io.File;

import static pl.skidam.automodpack.StaticVariables.*;

public class ScreenTools {

    public static class setTo { // Save screen's. Don't worry that minecraft didn't load yet, or you will crash server by executing screen's methods

        // TODO Make it work, it is much better code but it doesnt resolve the issue which we want to resolve by this class
//        public setTo(Screen screen) {
//            if (Checks.properlyLoaded()) Screens.setScreen(screen);
//        }

        public static void download() {
            if (Check.properlyLoaded()) Screens.DownloadScreen();
        }

        public static void restart(Screen parent, File gameDir) {
            if (Check.properlyLoaded()) Screens.RestartScreen(parent, gameDir);
        }

        public static void danger(Screen parent, String link, File modpackDir, File modpackContentFile) {
            if (Check.properlyLoaded()) Screens.DangerScreen(parent, link, modpackDir, modpackContentFile);
        }

        public static void error(String... error) {
            if (Check.properlyLoaded()) Screens.ErrorScreen(error);
        }

        public static void title() {
            if (Check.properlyLoaded()) Screens.TitleScreen();
        }
    }

    public static String getScreenString() {
        if (Check.properlyLoaded()) {
            Screen screen = Screens.getScreen();
            return screen.getTitle().getString().toLowerCase();
        }
        return "null";
    }

    public static Screen getScreen() {
        if (Check.properlyLoaded()) {
            return Screens.getScreen();
        }
        return null;
    }


    private static class Check {
        public static boolean properlyLoaded() {
            try {
                if (preload) return false;
                if (Platform.getEnvironmentType().equals("SERVER")) return false;
                if (MinecraftClient.getInstance() == null) return false;
                if (MinecraftClient.getInstance().currentScreen == null) return false;
                return true;
            } catch (Exception e) {
                return false;
            }
        }
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

        public static void DangerScreen(Screen parent, String link, File modpackDir, File modpackContentFile) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new DangerScreen(parent, link, modpackDir, modpackContentFile)));
        }

        public static void TitleScreen() {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new TitleScreen()));
        }

        public static void ErrorScreen(String... error) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new ErrorScreen(error)));
        }
    }
}
