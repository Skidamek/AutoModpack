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

import net.minecraft.MinecraftVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.utils.JarUtilities;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class GlobalVariables {
    public static final Logger LOGGER = LogManager.getLogger("AutoModpack");
    public static final String MOD_ID = "automodpack";
    public static String VERSION = JarUtilities.getModVersion("automodpack");

    //#if MC >= 11700
    public static String MC_VERSION = MinecraftVersion.CURRENT.getName();
    //#else
    //$$ public static String MC_VERSION = MinecraftVersion.field_25319.getName();
    //#endif
    public static final Path automodpackDir = Paths.get("./automodpack/");
    public static final Path modpacksDir = Paths.get(automodpackDir + File.separator + "modpacks");
    public static final Path clientConfigFile = Paths.get(automodpackDir + File.separator + "automodpack-client.json");
    public static final Path serverConfigFile = Paths.get(automodpackDir + File.separator + "automodpack-server.json");
    public static final Set<String> keyWordsOfDisconnect = new HashSet<>(Arrays.asList("install", "update", "download", "handshake", "incompatible", "outdated", "client", "version"));
    public static Path modsPath;
    public static String ClientLink;
    public static boolean preload;
    public static Path selectedModpackDir;
    public static String selectedModpackLink;
    public static Jsons.ServerConfigFields serverConfig;
    public static Jsons.ClientConfigFields clientConfig;
    public static boolean quest;
    public static boolean serverFullyStarted = false;
}
