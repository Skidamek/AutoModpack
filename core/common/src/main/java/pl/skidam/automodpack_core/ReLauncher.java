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

import pl.skidam.automodpack_core.ui.Windows;

import java.awt.*;
import java.nio.file.Path;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class ReLauncher {

    public static class Restart {

        public Restart(Path modpackDir, boolean fullDownload) {
            new Restart(modpackDir, "Successfully applied the modpack!", fullDownload);
        }

        public Restart(Path modpackDir, String guiMessage, boolean fullDownload) {
            String environment = new Loader().getEnvironmentType();
            boolean isClient = environment.equals("CLIENT");
            boolean isHeadless = GraphicsEnvironment.isHeadless();

            if (isClient) {
                if (preload && !isHeadless) {
                    new Windows().restartWindow(guiMessage);
                    return;
                }

                LOGGER.info("Restart your client!");
                System.exit(0);
            } else {
                LOGGER.info("Please restart the server to apply updates!");
                System.exit(0);
            }
        }
    }
}
