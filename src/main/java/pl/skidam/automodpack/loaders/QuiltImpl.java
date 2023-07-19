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

//#if QUILT
//$$ import com.google.gson.Gson;
//$$ import com.google.gson.JsonObject;
//$$ import com.google.gson.JsonSyntaxException;
//$$ import net.fabricmc.api.EnvType;
//$$ import net.fabricmc.loader.api.FabricLoader;
//$$ import org.quiltmc.loader.api.ModContainer;
//$$ import org.quiltmc.loader.api.LoaderValue;
//$$ import org.quiltmc.loader.api.QuiltLoader;
//$$ import org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader;
//$$
//$$ import java.io.*;
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Paths;
//$$ import java.util.Collection;
//$$ import java.nio.file.FileSystem;
//$$ import java.nio.file.Path;
//$$ import java.util.Objects;
//$$ import java.util.stream.Collectors;
//$$ import java.util.zip.ZipEntry;
//$$ import java.util.zip.ZipException;
//$$ import java.util.zip.ZipFile;
//$$
//$$ import static pl.skidam.automodpack.GlobalVariables.*;
//$$
//$$ @SuppressWarnings("deprecation")
//$$ public class QuiltImpl {
//$$
//$$    public static boolean isModLoaded(String modId) {
//$$         return QuiltLoader.isModLoaded(modId);
//$$    }
//$$
//$$    public static Collection getModList() {
//$$        Collection<ModContainer> modsList = QuiltLoader.getAllMods();
//$$
//$$        return modsList.stream().map(mod -> mod.metadata().id() + " " + mod.metadata().version()).collect(Collectors.toList());
//$$    }
//$$
//$$    public static String getLoaderVersion() {
//$$        ModContainer container = QuiltLoader.getModContainer("quilt_loader").isPresent() ? QuiltLoader.getModContainer("quilt_loader").get() : null;
//$$        return container != null ? container.metadata().version().raw() : null;
//$$    }
//$$
//$$     public static Path getModPath(String modId) {
//$$         ModContainer container = QuiltLoader.getModContainer(modId).isPresent() ? QuiltLoader.getModContainer(modId).get() : null;
//$$
//$$         if (container != null) {
//$$             for (Path path : Objects.requireNonNull(container.getSourcePaths().stream().findFirst().isPresent() ? container.getSourcePaths().stream().findFirst().get() : null)) {
//$$                 if (path == null) {
//$$                     LOGGER.error("Could not find jar file for " + modId);
//$$                     return null;
//$$                 }
//$$
//$$                 return path;
//$$             }
//$$         }
//$$         return null;
//$$     }
//$$
//$$      public static String getEnvironmentType() {
//$$         // get environment type on quilt loader
//$$         if (MinecraftQuiltLoader.getEnvironmentType() == EnvType.CLIENT) {
//$$             return "CLIENT";
//$$         } else if (MinecraftQuiltLoader.getEnvironmentType() == EnvType.SERVER) {
//$$             return "SERVER";
//$$         } else {
//$$             return "UNKNOWN";
//$$         }
//$$      }
//$$
//$$     public static String getModVersion(String modid) {
//$$         return QuiltLoader.getModContainer(modid).isPresent() ? QuiltLoader.getModContainer(modid).get().metadata().version().toString() : null;
//$$     }
//$$
//$$      public static String getModVersion(Path file) {
//$$         try {
//$$             ZipFile zipFile = new ZipFile(file.toFile());
//$$             ZipEntry entry = null;
//$$             if (zipFile.getEntry("quilt.mod.json") != null) {
//$$                 entry = zipFile.getEntry("quilt.mod.json");
//$$             } else if (zipFile.getEntry("fabric.mod.json") != null) {
//$$                 entry = zipFile.getEntry("fabric.mod.json");
//$$             }
//$$
//$$             if (entry != null) {
//$$                 Gson gson = new Gson();
//$$                 InputStream stream = zipFile.getInputStream(entry);
//$$                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
//$$                 JsonObject json = gson.fromJson(reader, JsonObject.class);
//$$
//$$                 // close everything
//$$                 reader.close();
//$$                 stream.close();
//$$                 zipFile.close();
//$$
//$$                 if (entry.getName().equals("fabric.mod.json")) {
//$$                     if (json.has("version")) {
//$$                         return json.get("version").getAsString();
//$$                     }
//$$                 } else if (json.has("quilt_loader")) {
//$$                     JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
//$$                     if (quiltLoader.has("version")) {
//$$                         return quiltLoader.get("version").getAsString();
//$$                     }
//$$                 }
//$$             }
//$$         } catch (ZipException ignored) {
//$$             return null;
//$$         } catch (IOException | JsonSyntaxException e) {
//$$             e.printStackTrace();
//$$         }
//$$
//$$         return null;
//$$     }
//$$
//$$    public static boolean isDevelopmentEnvironment() {
//$$         return QuiltLoader.isDevelopmentEnvironment();
//$$    }
//$$
//$$     public static String getModEnvironment(String modId) {
//$$         if (QuiltLoader.getModContainer(modId).isPresent()) {
//$$             LoaderValue env = QuiltLoader.getModContainer(modId).get().metadata().value("environment");
//$$             if (env == null) {
//$$                 return FabricLoader.getInstance().getModContainer(modId).isPresent() ?  FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getEnvironment().toString().toUpperCase() : "*";
//$$             }
//$$             return env.toString().toUpperCase();
//$$         }
//$$         return "UNKNOWN";
//$$    }
//$$
//$$     public static String getModEnvironmentFromNotLoadedJar(Path file) {
//$$         if (!Files.isRegularFile(file)) return null;
//$$         if (!file.getFileName().endsWith(".jar")) return null;
//$$
//$$         try {
//$$             ZipFile zipFile = new ZipFile(file.toFile());
//$$             ZipEntry entry = null;
//$$             if (zipFile.getEntry("quilt.mod.json") != null) {
//$$                 entry = zipFile.getEntry("quilt.mod.json");
//$$             } else if (zipFile.getEntry("fabric.mod.json") != null) {
//$$                 entry = zipFile.getEntry("fabric.mod.json");
//$$             }
//$$
//$$             if (entry != null) {
//$$                 Gson gson = new Gson();
//$$                 InputStream stream = zipFile.getInputStream(entry);
//$$                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
//$$                 JsonObject json = gson.fromJson(reader, JsonObject.class);
//$$
//$$                 // close everything
//$$                 reader.close();
//$$                 stream.close();
//$$                 zipFile.close();
//$$
//$$                 if (entry.getName().equals("fabric.mod.json")) {
//$$                     if (json.has("environment")) {
//$$                         return json.get("environment").getAsString().toUpperCase();
//$$                     }
//$$                 } else if (json.has("quilt_loader")) {
//$$                     JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
//$$                     if (quiltLoader.has("environment")) {
//$$                         return quiltLoader.get("environment").getAsString().toUpperCase();
//$$                     }
//$$
//$$                     // if not, we also can check in different place
//$$                     if (json.has("minecraft")) {
//$$                         JsonObject minecraft = json.get("minecraft").getAsJsonObject();
//$$                         if (minecraft.has("environment")) {
//$$                             return minecraft.get("environment").getAsString().toUpperCase();
//$$                         }
//$$                     }
//$$                 }
//$$             }
//$$         } catch (ZipException ignored) {
//$$             return "UNKNOWN";
//$$         } catch (IOException e) {
//$$             e.printStackTrace();
//$$         }
//$$
//$$         return "UNKNOWN";
//$$    }
//$$
//$$     public static String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
//$$         if (!Files.isRegularFile(file)) return null;
//$$         if (!file.getFileName().endsWith(".jar")) return null;
//$$         if (Objects.equals(getModEnvironmentFromNotLoadedJar(file), "UNKNOWN")) return null;
//$$
//$$         for (ModContainer modContainer : QuiltLoader.getAllMods()) {
//$$             FileSystem fileSys = modContainer.rootPath().getFileSystem();
//$$             Path modFile = Paths.get(fileSys.toString());
//$$             if (modFile.getFileName().equals(file.getFileName())) {
//$$                 return modContainer.metadata().id();
//$$             }
//$$         }
//$$
//$$         if (checkAlsoOutOfContainer) return getModIdFromNotLoadedJar(file);
//$$
//$$        return null;
//$$     }
//$$
//$$     public static String getModIdFromNotLoadedJar(Path file) {
//$$         try {
//$$             ZipFile zipFile = new ZipFile(file.toFile());
//$$             ZipEntry entry = null;
//$$             if (zipFile.getEntry("quilt.mod.json") != null) {
//$$                 entry = zipFile.getEntry("quilt.mod.json");
//$$             } else if (zipFile.getEntry("fabric.mod.json") != null) {
//$$                 entry = zipFile.getEntry("fabric.mod.json");
//$$             }
//$$
//$$             if (entry != null) {
//$$                 Gson gson = new Gson();
//$$                 InputStream stream = zipFile.getInputStream(entry);
//$$                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
//$$                 JsonObject json = gson.fromJson(reader, JsonObject.class);
//$$
//$$                 // close everything
//$$                 reader.close();
//$$                 stream.close();
//$$                 zipFile.close();
//$$
//$$                 if (entry.getName().equals("fabric.mod.json")) {
//$$                     if (json.has("id")) {
//$$                         return json.get("id").getAsString();
//$$                     }
//$$                 } else if (json.has("quilt_loader")) {
//$$                     JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
//$$                     if (quiltLoader.has("id")) {
//$$                         return quiltLoader.get("id").getAsString();
//$$                     }
//$$                 }
//$$             }
//$$         } catch (ZipException ignored) {
//$$             return null;
//$$         } catch (IOException e) {
//$$             e.printStackTrace();
//$$             return null;
//$$         }
//$$
//$$         return null;
//$$     }
//$$ }
//#endif