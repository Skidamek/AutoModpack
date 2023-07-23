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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pl.skidam.automodpack.GlobalVariables;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class MmcPackMagic {
    public static final Path mmcPackFile = Paths.get("./../mmc-pack.json");
    public static final List<String> mcVerUIDs = Arrays.asList("net.minecraft", "net.fabricmc.intermediary");
    public static final List<String> modLoaderUIDs = Arrays.asList("net.fabricmc.fabric-loader", "org.quiltmc.quilt-loader", "net.minecraftforge");

    public static JsonObject getJson() throws IOException {
        return Json.fromFile(mmcPackFile);
    }

    public static void changeVersion(List<String> listOfUIDs, String newVersion) throws IOException {
        if (newVersion == null || listOfUIDs == null) {
            return;
        }

        if (!Files.exists(mmcPackFile)) {
            return;
        }

        JsonObject newJson = getJson().getAsJsonObject();
        if (newJson == null) {
            return;
        }

        JsonArray components = newJson.getAsJsonArray("components");

        for (JsonElement comp : components) {
            JsonObject component = comp.getAsJsonObject();
            if (listOfUIDs.contains(component.get("uid").getAsString())) {
                String oldVersion = component.get("version").getAsString();
                component.remove("version");
                component.addProperty("version", newVersion);
                if (!oldVersion.equals(newVersion)) {
                    GlobalVariables.LOGGER.info("Changed version of " + component.get("uid").getAsString() + " to " + newVersion);
                }
            }
        };

        // change components of mmcPackJson to our components
        newJson.remove("components");
        newJson.add("components", components);

        Files.write(mmcPackFile, newJson.toString().getBytes());
    }
}
