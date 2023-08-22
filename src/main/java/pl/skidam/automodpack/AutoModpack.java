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

package pl.skidam.automodpack;

import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.loaders.Loader;
import pl.skidam.automodpack.modpack.Modpack;
import pl.skidam.automodpack.networking.ModPackets;

import static pl.skidam.automodpack.GlobalVariables.*;
import pl.skidam.automodpack.modpack.Commands;

public class AutoModpack {

    public static void onInitialize() {

        preload = false;

        long start = System.currentTimeMillis();
        LOGGER.info("Launching AutoModpack...");

        if (Loader.getEnvironmentType().equals("SERVER")) {
            if (serverConfig.generateModpackOnStart) {
                LOGGER.info("Generating modpack...");
                long genStart = System.currentTimeMillis();
                if (Modpack.generate()) {
                    LOGGER.info("Modpack generated! took " + (System.currentTimeMillis() - genStart) + "ms");
                } else {
                    LOGGER.error("Failed to generate modpack!");
                }
            }
            ModPackets.registerS2CPackets();
        } else {
            ModPackets.registerC2SPackets();
            new AudioManager();
        }

        Commands.register();

        LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
    }
}