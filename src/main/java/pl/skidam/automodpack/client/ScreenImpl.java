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
import net.minecraft.util.Util;
import pl.skidam.automodpack.client.ui.*;
import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.loader.LoaderManager;
import pl.skidam.automodpack_core.screen.ScreenService;
import pl.skidam.automodpack_core.utils.DownloadManager;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_core.utils.UpdateType;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static pl.skidam.automodpack_common.GlobalVariables.preload;

public class ScreenImpl implements ScreenService {

    private void callScreen(String screenName, Object... args) {
        if (ScreenImpl.Check.properlyLoaded()) {
            try {
                Method method = Arrays.stream(ScreenImpl.Screens.class.getDeclaredMethods())
                        .filter(m -> m.getName().equals(screenName))
                        .findFirst()
                        .orElseThrow(() -> new NoSuchMethodException("No method found with name " + screenName));

                if (method.getParameterCount() != args.length) {
                    throw new IllegalArgumentException("Incorrect number of arguments for method " + screenName);
                }


                Util.getMainWorkerExecutor().execute(() -> {
                    try {
                        method.invoke(null, args);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void download(Object... args) {
        callScreen("download", args);
    }

    @Override
    public void fetch(Object... args) {
        callScreen("fetch", args);
    }

    @Override
    public void changelog(Object... args) {
        callScreen("changelog", args);
    }

    @Override
    public void restart(Object... args) {
        callScreen("restart", args);
    }

    @Override
    public void danger(Object... args) {
        callScreen("danger", args);
    }

    @Override
    public void error(Object... args) {
        callScreen("error", args);
    }

    @Override
    public void menu(Object... args) {
        callScreen("menu", args);
    }

    @Override
    public void title(Object... args) {
        callScreen("title", args);
    }

    @Override
    public Optional<String> getScreenString() {
        if (Check.properlyLoaded()) {
            Screen screen = Screens.getScreen();
            return Optional.of(screen.getTitle().getString().toLowerCase());
        }
        return Optional.of("null");
    }

    @Override
    public Optional<Object> getScreen() {
        if (Check.properlyLoaded()) {
            return Optional.ofNullable(Screens.getScreen());
        }
        return Optional.empty();
    }

    @Override
    public Optional<Boolean> properlyLoaded() {
        return Optional.of(Check.properlyLoaded());
    }

    // These have to be in a separate class, or game gonna crash

    private static class Check {
        public static boolean properlyLoaded() {
            try {
                if (preload) return false;
                if (new LoaderManager().getEnvironmentType().equals("SERVER")) return false;
                if (MinecraftClient.getInstance() == null) return false;
                if (MinecraftClient.getInstance().currentScreen == null) return false;
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static class Screens {
        private static Screen getScreen() {
            return MinecraftClient.getInstance().currentScreen;
        }

        public static void setScreen(Screen screen) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(screen));
        }

        // Even tho these methods are 'unused' there are not
        // Look at callScreen method

        public static void download(DownloadManager downloadManager, String header) {
            Screens.setScreen(new DownloadScreen(downloadManager, header));
        }
        public static void fetch(FetchManager fetchManager) {
            Screens.setScreen(new FetchScreen(fetchManager));
        }
        public static void changelog(Screen parent, Path modpackDir, Changelogs changelog) {
            Screens.setScreen(new ChangelogScreen(parent, modpackDir, changelog));
        }

        public static void restart(Path modpackDir, UpdateType updateType, Changelogs changelogs) {
            Screens.setScreen(new RestartScreen(modpackDir, updateType, changelogs));
        }

        public static void danger(Screen parent, String link, Path modpackDir, Path modpackContentFile) {
            Screens.setScreen(new DangerScreen(parent, link, modpackDir, modpackContentFile));
        }

        public static void error(String... error) {
            Screens.setScreen(new ErrorScreen(error));
        }

        public static void title() {
            Screens.setScreen(new TitleScreen());
        }

        public static void menu() {
            Screens.setScreen(new MenuScreen());
        }
    }
}
