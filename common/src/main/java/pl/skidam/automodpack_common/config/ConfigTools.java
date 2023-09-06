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

package pl.skidam.automodpack_common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class ConfigTools {

    public static Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();

    public static <T> T getConfigObject(Class<T> configClass) {
        T object = null;
        try {
            object = configClass.getConstructor().newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return object;
    }

    // Config stuff
    public static <T> T loadConfig(Path configFile, Class<T> configClass) {
        try {
            if (!Files.isDirectory(configFile.getParent())) {
                 Files.createDirectories(configFile.getParent());
            }

            if (Files.isRegularFile(configFile)) {
                String json = Files.readString(configFile);
                T obj = GSON.fromJson(json, configClass);
                saveConfig(configFile, obj);
                return obj;
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't load config! " + configClass);
            e.printStackTrace();
        }

        try { // create new config
            T obj = getConfigObject(configClass);
            saveConfig(configFile, obj);
            return obj;
        } catch (Exception e) {
            LOGGER.error("Invalid config class! " + configClass);
            e.printStackTrace();
            return null;
        }
    }

    public static void saveConfig(Path configFile, Object configObject) {
        try {
            if (!Files.isDirectory(configFile.getParent())) {
                Files.createDirectories(configFile.getParent());
            }

            Files.writeString(configFile, GSON.toJson(configObject), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("Couldn't save config! " + configObject.getClass());
            e.printStackTrace();
        }
    }


    // Modpack content stuff
    public static Jsons.ModpackContentFields loadModpackContent(Path modpackContentFile) {
        try {
            if (Files.isRegularFile(modpackContentFile)) {
                String json = Files.readString(modpackContentFile);
                return GSON.fromJson(json, Jsons.ModpackContentFields.class);
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't load modpack content!");
            e.printStackTrace();
        }
        return null;
    }

    public static void saveModpackContent(Path modpackContentFile, Jsons.ModpackContentFields configObject) {
        try {
            if (!Files.isDirectory(modpackContentFile.getParent())) {
                Files.createDirectories(modpackContentFile.getParent());
            }

            Files.writeString(modpackContentFile, GSON.toJson(configObject), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("Couldn't save modpack content! " + configObject.getClass());
            e.printStackTrace();
        }
    }
}
