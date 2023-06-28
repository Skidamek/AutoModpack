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

import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.modpack.Modpack;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static pl.skidam.automodpack.GlobalVariables.*;

public class ModpackContentTools {
    public static String getFileType(String file, Jsons.ModpackContentFields list) {
        for (Jsons.ModpackContentFields.ModpackContentItem item : list.list) {
            if (item.file.contains(file)) { // compare file absolute path if it contains item.file
                return item.type;
            }
        }
        return "null";
    }

    public static String getModpackLink(String modpack) {
        if (modpack == null || modpack.isEmpty()) {
            LOGGER.warn("Modpack name is null or empty!");
            return null;
        }

        Path modpackDir = getModpackDir(modpack);

        if (Objects.isNull(modpackDir) || !Files.exists(modpackDir) || !Files.isDirectory(modpackDir)) {
            LOGGER.warn("Modpack {} doesn't exist!", modpack);
            return null;
        }

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(modpackDir)) {
            for (Path path : directoryStream) {
                if (Objects.equals(path.getFileName(), Modpack.hostModpackContentFile.getFileName())) {
                    Jsons.ModpackContentFields modpackContent = ConfigTools.loadConfig(path, Jsons.ModpackContentFields.class);
                    if (modpackContent != null && modpackContent.link != null && !modpackContent.link.isEmpty()) {
                        return modpackContent.link;
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error reading modpack directory: {}", e.getMessage());
        }

        return null;
    }

    public static Path getModpackDir(String modpack) {
        if (modpack == null || modpack.equals("")) {
            LOGGER.warn("Modpack name is null or empty!");
            return null;
        }

        // eg. modpack = 192.168.0.113-30037 `directory`

        return Paths.get(modpacksDir + File.separator + modpack);
    }

    public static Map<String, Path> getListOfModpacks() {
        Map<String, Path> map = new HashMap<>();
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(modpacksDir)) {
            for (Path path : directoryStream) {
                if (Files.isDirectory(path)) {
                    map.put(path.getFileName().toString(), path);
                }
            }
        } catch (IOException e) {
            // Handle the exception if necessary
            e.printStackTrace();
        }

        return map;
    }

    public static Path getModpackContentFile(Path modpackDir) {
        if (!Files.exists(modpackDir)) {
            return null;
        }
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(modpackDir)) {
            for (Path path : directoryStream) {
                if (Objects.equals(path.getFileName(), Modpack.hostModpackContentFile.getFileName())) {
                    return path;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    public static String getStringOfAllHashes(Jsons.ModpackContentFields modpackContent) {
        StringBuilder sb = new StringBuilder();
        for (Jsons.ModpackContentFields.ModpackContentItem item : modpackContent.list) {
            sb.append(item.sha1).append("\n");
        }
        return sb.toString();
    }
}
