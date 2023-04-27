package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.client.ui.*;

import java.io.File;

import static pl.skidam.automodpack.StaticVariables.preload;

public class ScreenTools {

    public static class setTo { // Save screen's. Don't worry that minecraft didn't load yet, or you will crash server by executing screen's methods

        // TODO Make it work
//        public setTo(Screen screen) {
//            if (Checks.properlyLoaded()) Screens.setScreen(screen);
//        }

        public static void download() {
            if (Check.properlyLoaded()) Screens.setScreen(new DownloadScreen());
        }
        public static void fetch() {
            if (Check.properlyLoaded()) Screens.setScreen(new FetchScreen());
        }

        public static void restart(Screen parent, File gameDir) {
            if (Check.properlyLoaded()) Screens.setScreen(new RestartScreen(parent, gameDir));
        }

        public static void danger(Screen parent, String link, File modpackDir, File modpackContentFile) {
            if (Check.properlyLoaded()) Screens.setScreen(new DangerScreen(parent, link, modpackDir, modpackContentFile));
        }

        public static void error(String... error) {
            if (Check.properlyLoaded()) Screens.setScreen(new ErrorScreen(error));
        }

        public static void title() {
            if (Check.properlyLoaded()) Screens.setScreen(new TitleScreen());
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
    }
}
