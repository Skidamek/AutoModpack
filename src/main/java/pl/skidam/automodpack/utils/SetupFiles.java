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

package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.loaders.Loader;

import java.io.File;

public class SetupFiles {
    public SetupFiles() {
        File AMdir = new File("./automodpack/");
        // Check if AutoModpack path exists
        if (!AMdir.exists()) {
            AMdir.mkdirs();
        }

        if (Loader.getEnvironmentType().equals("SERVER")) {
            server();
        }

        if (Loader.getEnvironmentType().equals("CLIENT")) {
            client();
        }
    }

    private void server() {

    }

    private void client() {
        File modpacks = new File("./automodpack/modpacks/");
        if (!modpacks.exists()) {
            modpacks.mkdirs();
        }
    }
}