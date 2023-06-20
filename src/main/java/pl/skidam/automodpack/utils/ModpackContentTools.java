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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static pl.skidam.automodpack.StaticVariables.*;

public class ModpackContentTools {
    public static String getFileType(String file, Jsons.ModpackContentFields list) {
        for (Jsons.ModpackContentFields.ModpackContentItems item : list.list) {
            if (item.file.contains(file)) { // compare file absolute path if it contains item.file
                return item.type;
            }
        }
        return "null";
    }

    public static String getModpackLink(String modpack) {
        File modpackDir = getModpackDir(modpack);

        if (!modpackDir.exists() || !modpackDir.isDirectory()) {
            LOGGER.warn("Modpack {} doesn't exist!", modpack);
            return null;
        }

        for (File file : Objects.requireNonNull(modpackDir.listFiles())) {
            if (file.getName().equals(Modpack.hostModpackContentFile.getName())) {
                Jsons.ModpackContentFields modpackContent = ConfigTools.loadConfig(file, Jsons.ModpackContentFields.class);
                assert modpackContent != null;
                if (modpackContent.link != null && !modpackContent.link.equals("")) {
                    return modpackContent.link;
                }
            }
        }
        return null;
    }

    public static File getModpackDir(String modpack) {
        if (modpack == null || modpack.equals("")) {
            LOGGER.warn("Modpack name is null or empty!");
            return null;
        }

        // eg. modpack = 192.168.0.113-30037 `directory`

        return new File(modpacksDir + File.separator + modpack);
    }

    public static Map<String, File> getListOfModpacks() {
        Map<String, File> map = new HashMap<>();
        for (File file : Objects.requireNonNull(modpacksDir.listFiles())) {
            if (file.isDirectory()) {
                map.put(file.getName(), file);
            }
        }
        return map;
    }

    public static File getModpackContentFile(File modpackDir) {
        File[] files = modpackDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals(Modpack.hostModpackContentFile.getName())) {
                    return file;
                }
            }
        }
        return null;
    }

    public static String getStringOfAllHashes(Jsons.ModpackContentFields modpackContent) {
        StringBuilder sb = new StringBuilder();
        for (Jsons.ModpackContentFields.ModpackContentItems item : modpackContent.list) {
            sb.append(item.sha1).append("\n");
        }
        return sb.toString();
    }
}
