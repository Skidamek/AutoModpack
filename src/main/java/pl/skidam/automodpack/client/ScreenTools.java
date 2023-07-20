/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import pl.skidam.automodpack.loaders.Loader;
import pl.skidam.automodpack.client.ui.*;

import java.nio.file.Path;

import static pl.skidam.automodpack.GlobalVariables.preload;

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
        public static void changelog(Screen parent, Path modpackDir) {
            if (Check.properlyLoaded()) Screens.setScreen(new ChangelogScreen(parent, modpackDir));
        }

        public static void restart(Path modpackDir, boolean fullDownload) {
            if (Check.properlyLoaded()) Screens.setScreen(new RestartScreen(modpackDir, fullDownload));
        }

        public static void danger(Screen parent, String link, Path modpackDir, Path modpackContentFile) {
            if (Check.properlyLoaded()) Screens.setScreen(new DangerScreen(parent, link, modpackDir, modpackContentFile));
        }

        public static void error(String... error) {
            if (Check.properlyLoaded()) Screens.setScreen(new ErrorScreen(error));
        }

        public static void title() {
            if (Check.properlyLoaded()) Screens.setScreen(new TitleScreen());
        }

        public static void menu() {
            if (Check.properlyLoaded()) Screens.setScreen(new MenuScreen());
        }
    }

    // Kinda bad I know, at latest it won't crash server lol...
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
                if (Loader.getEnvironmentType().equals("SERVER")) return false;
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
