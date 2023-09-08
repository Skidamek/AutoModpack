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
import pl.skidam.automodpack_core.loader.LoaderService;
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

    @Override
    public void download(Object... args) {
        if (!Check.properlyLoaded()) {
            return;
        }

        Screens.download(args[0], args[1]);
    }

    @Override
    public void fetch(Object... args) {
        if (!Check.properlyLoaded()) {
            return;
        }

        Screens.fetch(args[0]);
    }

    @Override
    public void changelog(Object... args) {
        if (!Check.properlyLoaded()) {
            return;
        }

        Screens.changelog(args[0], args[1], args[2]);
    }

    @Override
    public void restart(Object... args) {
        if (!Check.properlyLoaded()) {
            return;
        }

        Screens.restart(args[0], args[1], args[2]);
    }

    @Override
    public void danger(Object... args) {
        if (!Check.properlyLoaded()) {
            return;
        }

        Screens.danger(args[0], args[1], args[2], args[3]);
    }

    @Override
    public void error(Object... args) {
        if (!Check.properlyLoaded()) {
            return;
        }

        Screens.error(args);
    }

    @Override
    public void menu(Object... args) {
        if (!Check.properlyLoaded()) {
            return;
        }

        Screens.menu();
    }

    @Override
    public void title(Object... args) {
        if (!Check.properlyLoaded()) {
            return;
        }

        Screens.title();
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

    // These have to be in a separate class, or game gonna crash
    private static class Check {
        public static boolean properlyLoaded() {
            try {
                if (preload) return false;
                if (new LoaderManager().getEnvironmentType() != LoaderService.EnvironmentType.CLIENT) return false;
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
            Util.getMainWorkerExecutor().execute(() -> MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(screen)));
        }

        // Even tho these methods are 'unused' there are not
        // Look at callScreen method

        public static void download(Object downloadManager, Object header) {
            Screens.setScreen(new DownloadScreen((DownloadManager) downloadManager, (String) header));
        }
        public static void fetch(Object fetchManager) {
            Screens.setScreen(new FetchScreen((FetchManager) fetchManager));
        }
        public static void changelog(Object parent, Object modpackDir, Object changelog) {
            Screens.setScreen(new ChangelogScreen((Screen) parent, (Path) modpackDir, (Changelogs) changelog));
        }

        public static void restart(Object modpackDir, Object updateType, Object changelogs) {
            Screens.setScreen(new RestartScreen((Path) modpackDir, (UpdateType) updateType, (Changelogs) changelogs));
        }

        public static void danger(Object parent, Object link, Object modpackDir, Object modpackContentFile) {
            Screens.setScreen(new DangerScreen((Screen) parent, (String) link, (Path) modpackDir, (Path) modpackContentFile));
        }

        public static void error(Object... error) {
            Screens.setScreen(new ErrorScreen(Arrays.toString(error)));
        }

        public static void title() {
            Screens.setScreen(new TitleScreen());
        }

        public static void menu() {
            Screens.setScreen(new MenuScreen());
        }
    }
}
