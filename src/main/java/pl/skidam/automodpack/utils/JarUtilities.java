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
import java.nio.file.Path;

import static pl.skidam.automodpack.StaticVariables.LOGGER;

public class JarUtilities {

    // Most of the things here are got from Platform class but to have better code readability I decided to return them here

    /**
     * @param modId unique mod id
     * @return jar file name of mod with given modId
     */

    public static Path getModJarPath(String modId) {
        File jarDir = Loader.getModPath(modId);

        if (jarDir == null) {
            LOGGER.error("Could not find jar file for " + modId);
            return null;
        }

        return jarDir.toPath().toAbsolutePath();
    }

    public static String getJarFileOfMod(String modId) {
        File jarDir = Loader.getModPath(modId);

        if (jarDir == null) {
            LOGGER.error("Could not find jar file for " + modId);
            return null;
        }

        return jarDir.getName(); // returns name of the file
    }

    /**
     * @param jarFile path to jar file (mod)
     * @return unique id of mod
     */
    public static String getModIdFromJar(File jarFile, boolean checkAlsoOutOfContainer) {
        return Loader.getModIdFromLoadedJar(jarFile, checkAlsoOutOfContainer);
    }

    /**
     * @param modId unique id of some mod
     * @return mod version of mod with given modId
     */
    public static String getModVersion(String modId) {
        return Loader.getModVersion(modId);
    }

    /**
     * @param file path to jar file (mod)
     * @return mod version of mod from given jar file
     */
    public static String getModVersion(File file) {
        return Loader.getModVersion(file);
    }

    /**
     * @return Returns mods directory (exactly the directory where `automodpack` mod is loaded)
     */

    public static File getModsDirectory() {
        File modsPath = new File("./mods/");
        if (!Loader.isDevelopmentEnvironment()) {
            File jarDir = Loader.getModPath("automodpack"); // we will use automodpack because it is always present

            modsPath = jarDir.getParentFile(); // get parent directory, which should be a mods directory

            if (!modsPath.getName().equals("mods")) {
                LOGGER.warn("Found external mods folder ({})", modsPath);
            }
        }
        return modsPath;
    }
}