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

import pl.skidam.automodpack_common.config.Jsons;
import pl.skidam.automodpack_core.loader.LoaderManager;
import pl.skidam.automodpack_server.modpack.Modpack;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static pl.skidam.automodpack_common.GlobalVariables.*;
import static pl.skidam.automodpack_server.modpack.Modpack.Content.*;

public class ModpackGenAdditions {

    public static boolean generate() {
        boolean generated = Modpack.generate();

        if (!generated) {
            return false;
        }

        if (serverConfig.autoExcludeServerSideMods) {
            autoExcludeServerMods(Modpack.Content.list);
            saveModpackContent();
            restartFileChecker();
        }


        return true;
    }


    private static void autoExcludeServerMods(List<Jsons.ModpackContentFields.ModpackContentItem> list) {

        List<String> removeSimilar = new ArrayList<>();

        Collection modList = new LoaderManager().getModList();

        if (modList == null) {
            LOGGER.error("Failed to get mod list!");
            return;
        }

        for (Object mod : modList) {
            String modId = mod.toString().split(" ")[0]; // mod is  "modid (version)" so we remove everything after space to get modid (modid can't have space in it)
            String modEnv = new LoaderManager().getModEnvironment(modId).toUpperCase();
            if (modEnv.equals("SERVER")) {
                list.removeIf(modpackContentItems -> {
                    if (modpackContentItems.modId == null) return false;
                    if (modpackContentItems.modId.equals(modId)) {
                        LOGGER.info("Mod {} has been auto excluded from modpack because it is server side mod", modId);
                        removeSimilar.add(modId);
                        return true;
                    }
                    return false;
                });
            }
        }

        for (String modId : removeSimilar) {
            list.removeIf(modpackContentItems -> {
                if (modpackContentItems.type.equals("mod")) return false;
                Path contentFile = Paths.get(hostModpackDir + File.separator + "mods" + File.separator + modpackContentItems.file);
                String contentFileName = String.valueOf(contentFile.getFileName());
                if (contentFileName.contains(modId)) {
                    LOGGER.info("File {} has been auto excluded from modpack because mod of this file is already excluded", contentFileName);
                    return true;
                }
                return false;
            });
        }
    }
}
