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

package pl.skidam.automodpack_core;

import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.loader.LoaderManager;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.ui.Windows;
import pl.skidam.automodpack_core.utils.UpdateType;

import java.awt.*;
import java.nio.file.Path;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class ReLauncher {

    private static final String genericMessage = "Successfully applied the modpack!";

    public static class Restart {

        public Restart() {
            new Restart(null, genericMessage, null, null);
        }

        public Restart(String guiMessage) {
            new Restart(null, guiMessage, null, null);
        }

        public Restart(UpdateType updateType) {
            new Restart(null, genericMessage, updateType, null);
        }

        public Restart(String guiMessage, UpdateType updateType) {
            new Restart(null, guiMessage, updateType, null);
        }

        public Restart(UpdateType updateType, Changelogs changelogs) {
            new Restart(null, genericMessage, updateType, changelogs);
        }

        public Restart(Path modpackDir, UpdateType updateType, Changelogs changelogs) {
            new Restart(modpackDir, genericMessage, updateType, changelogs);
        }

        public Restart(Path modpackDir, String guiMessage, UpdateType updateType, Changelogs changelogs) {
            boolean isClient = new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.CLIENT;
            boolean isHeadless = GraphicsEnvironment.isHeadless();

            if (isClient) {
                if (!preload && updateType != null) {
                    if (new ScreenManager().getScreenString().isPresent() && !new ScreenManager().getScreenString().get().toLowerCase().contains("restartscreen")) {
                        new ScreenManager().restart(modpackDir, updateType, changelogs);
                        return;
                    }
                }

                if (preload && !isHeadless) {
                    new Windows().restartWindow(guiMessage);
                    return;
                }

                LOGGER.info("Restart your client!");
            } else {
                LOGGER.info("Please restart the server to apply updates!");
            }

            System.exit(0);
        }
    }
}
