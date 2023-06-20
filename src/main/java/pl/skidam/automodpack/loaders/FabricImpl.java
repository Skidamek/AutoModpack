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

package pl.skidam.automodpack.loaders;

//#if FABRIC
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.*;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static pl.skidam.automodpack.StaticVariables.*;

public class FabricImpl {
    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static Collection getModList() {
        Collection<ModContainer> modsList = FabricLoader.getInstance().getAllMods();
        return modsList;
    }

    public static File getModPath(String modId) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(modId);

            if (container.isPresent()) {
                ModContainer modContainer = container.get();
                Path jarPath = modContainer.getRootPaths().stream().findFirst().isPresent() ? modContainer.getRootPaths().stream().findFirst().get() : null;

                if (jarPath == null) {
                    LOGGER.error("Could not find jar file for " + modId);
                    return null;
                }

                FileSystem fileSystem = jarPath.getFileSystem();

                return new File(fileSystem.toString());
            }
        }
        return null;
    }

    public static String getEnvironmentType() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            return "CLIENT";
        } else if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            return "SERVER";
        } else {
            return "UNKNOWN";
        }
    }

    public static String getModEnvironmentFromNotLoadedJar(File file) {
        if (!file.isFile()) return null;
        if (!file.getName().endsWith(".jar")) return null;

        try {
            ZipFile zipFile = new ZipFile(file);
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            }

            if (entry != null) {
                Gson gson = new Gson();
                InputStream stream = zipFile.getInputStream(entry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                JsonObject json = gson.fromJson(reader, JsonObject.class);

                // close everything
                reader.close();
                stream.close();
                zipFile.close();

                if (json.has("environment")) {
                    return json.get("environment").getAsString().toUpperCase();
                }
            }
        } catch (ZipException ignored) {
            return "UNKNOWN";
        } catch (IOException e) {
            LOGGER.error("Failed to get mod env from file: " + file.getName());
            e.printStackTrace();
        }

        return "UNKNOWN";
    }

    public static String getModVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).isPresent() ? FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getVersion().getFriendlyString() : null;
    }

    public static String getModVersion(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            }

            if (entry != null) {
                Gson gson = new Gson();
                InputStream stream = zipFile.getInputStream(entry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                JsonObject json = gson.fromJson(reader, JsonObject.class);

                // close everything
                reader.close();
                stream.close();
                zipFile.close();

                if (json.has("version")) {
                    return json.get("version").getAsString();
                }
            }
        } catch (ZipException ignored) {
            return "UNKNOWN";
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.error("Failed to get mod version from file: " + file.getName());
            e.printStackTrace();
        }

        return null;
    }
    public static boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    public static String getModEnvironment(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).isPresent() ?  FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getEnvironment().toString().toUpperCase() : "*";
    }

    public static String getModIdFromLoadedJar(File file, boolean checkAlsoOutOfContainer) {
        for (ModContainer modContainer : FabricLoader.getInstance().getAllMods()) {
            FileSystem fileSys = modContainer.getRootPaths().get(0).getFileSystem();
            File modFile = new File(fileSys.toString());
            if (modFile.getName().equals(file.getName())) {
                return modContainer.getMetadata().getId();
            }
        }

        if (checkAlsoOutOfContainer) return getModIdFromNotLoadedJar(file);

        return null;
    }

    public static String getModIdFromNotLoadedJar(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            }

            if (entry != null) {
                Gson gson = new Gson();
                InputStream stream = zipFile.getInputStream(entry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                JsonObject json = gson.fromJson(reader, JsonObject.class);

                // close everything
                reader.close();
                stream.close();
                zipFile.close();

                if (json.has("id")) {
                    return json.get("id").getAsString();
                }
            }
        } catch (ZipException ignored) {
            return null;
        } catch (IOException e) {
            LOGGER.error("Failed to get mod id from file: " + file.getName());
            e.printStackTrace();
            return null;
        }

        return null;
    }
}
//#endif