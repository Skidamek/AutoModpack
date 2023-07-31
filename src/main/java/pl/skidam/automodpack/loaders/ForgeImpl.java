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

//#if FORGE
//$$ import java.io.BufferedReader;
//$$ import java.io.InputStreamReader;
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//$$ import java.util.ArrayList;
//$$ import java.util.Collection;
//$$ import java.util.List;
//$$ import java.util.zip.ZipEntry;
//$$ import java.util.zip.ZipException;
//$$ import java.util.zip.ZipFile;
//$$
//$$ import net.minecraftforge.api.distmarker.Dist;
//$$ import net.minecraftforge.fml.loading.FMLLoader;
//$$ import net.minecraftforge.fml.ModList;
//$$ import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
//$$
//$$ import static pl.skidam.automodpack.GlobalVariables.LOGGER;
//$$
//$$ public class ForgeImpl {
//$$     public static boolean isDevelopmentEnvironment() {
//$$         return !FMLLoader.isProduction();
//$$     }
//$$
//$$     public static boolean isModLoaded(String modId) {
//$$         return ModList.get().isLoaded(modId);
//$$     }
//$$
//$$     public static String getLoaderVersion() {
//$$         return FMLLoader.versionInfo().forgeVersion();
//$$    }
//$$
//$$     public static Collection getModList() {
//$$         List<ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();
//$$
//$$         List<String> modList = new ArrayList<>();
//$$
//$$         for (ModInfo modInfo : modInfos) {
//$$             modList.add(modInfo.getModId() + " " + modInfo.getVersion().toString());
//$$         }
//$$
//$$         return modList;
//$$     }
//$$
//$$     public static Path getModPath(String modId) {
//$$         List<ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();
//$$
//$$         for (ModInfo modInfo : modInfos) {
//$$            if (modInfo.getModId().equals(modId)) {
//$$                return modInfo.getOwningFile().getFile().getFilePath();
//$$            }
//$$         }
//$$
//$$         return null;
//$$     }
//$$
//$$
//$$     public static String getModEnvironment(String modId) {
//$$         List<ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();
//$$
//$$         try {
//$$            for (ModInfo modInfo : modInfos) {
//$$                if (modInfo.getModId().equals(modId)) {
//$$                    Path file = modInfo.getOwningFile().getFile().getFilePath().toAbsolutePath().normalize();
//$$                    if (file.toFile().exists() && Files.isRegularFile(file)) {
//$$                        return getModEnvironmentFromNotLoadedJar(file);
//$$                    }
//$$                }
//$$            }
//$$        } catch (UnsupportedOperationException ignored) {
//$$
//$$        }
//$$
//$$        return "UNKNOWN";
//$$    }
//$$
//$$    public static String getModEnvironmentFromNotLoadedJar(Path file) {
//$$         try {
//$$             ZipFile zipFile = new ZipFile(file.toFile());
//$$             ZipEntry entry = zipFile.getEntry("META-INF/mods.toml");
//$$
//$$             if (entry != null) {
//$$                BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));
//$$                String line;
//$$                while ((line = reader.readLine()) != null) {
//$$                    if (line.startsWith("side")) {
//$$                        String[] split = line.split("=");
//$$                         if (split.length > 1) {
//$$                             return split[1].replaceAll("\"", "").trim();
//$$                         }
//$$                     }
//$$                 }
//$$             }
//$$         } catch (ZipException ignored) {
//$$             return "UNKNOWN";
//$$         } catch (Exception e) {
//$$             LOGGER.error("Failed to get mod environment from file: " + file);
//$$             e.printStackTrace();
//$$         }
//$$
//$$         return "UNKNOWN";
//$$    }
//$$
//$$    public static String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
//$$         List<ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();
//$$
//$$         for (ModInfo modInfo : modInfos) {
//$$            if (modInfo.getOwningFile().getFile().getFilePath().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
//$$                 return modInfo.getModId();
//$$             }
//$$         }
//$$
//$$         if (checkAlsoOutOfContainer) {
//$$            return getModIdFromNotLoadedJar(file);
//$$         }
//$$
//$$         return null;
//$$    }
//$$
//$$     public static String getModIdFromNotLoadedJar(Path file) {
//$$        try {
//$$            ZipFile zipFile = new ZipFile(file.toFile());
//$$             ZipEntry entry = zipFile.getEntry("META-INF/mods.toml");
//$$
//$$            if (entry != null) {
//$$                BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));
//$$                String line;
//$$                while ((line = reader.readLine()) != null) {
//$$                    if (line.startsWith("modId")) {
//$$                        String[] split = line.split("=");
//$$                        if (split.length > 1) {
//$$                             return split[1].replaceAll("\"", "").trim();
//$$                         }
//$$                     }
//$$                 }
//$$            }
//$$         } catch (ZipException ignored) {
//$$             return null;
//$$        } catch (Exception e) {
//$$            LOGGER.error("Failed to get mod id from file: " + file.getFileName());
//$$            e.printStackTrace();
//$$             return null;
//$$         }
//$$
//$$        return null;
//$$   }
//$$
//$$     public static String getModVersion(String modId) {
//$$         ModInfo modInfo = FMLLoader.getLoadingModList().getMods().stream().filter(mod -> mod.getModId().equals(modId)).findFirst().orElse(null);
//$$         if (modInfo == null) {
//$$             return null;
//$$         }
//$$
//$$         return modInfo.getVersion().toString();
//$$    }
//$$
//$$     public static String getModVersion(Path file) {
//$$        try {
//$$            ZipFile zipFile = new ZipFile(file.toFile());
//$$            ZipEntry modsTomlEntry = zipFile.getEntry("META-INF/mods.toml");
//$$
//$$           if (modsTomlEntry != null) {
//$$               BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(modsTomlEntry)));
//$$                String line;
//$$               while ((line = reader.readLine()) != null) {
//$$                   if (line.startsWith("version")) {
//$$                       String[] split = line.split("=");
//$$                       if (split.length > 1) {
//$$                           String version = split[1].substring(0, split[1].lastIndexOf("\""));
//$$                           version = version.replaceAll("\"", "").trim();
//$$
//$$                           if ("${file.jarVersion}".equals(version)) {
//$$                               ZipEntry manifestEntry = zipFile.getEntry("META-INF/MANIFEST.MF");
//$$                               if (manifestEntry != null) {
//$$                                   BufferedReader manifestReader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(manifestEntry)));
//$$                                   String manifestLine;
//$$                                   while ((manifestLine = manifestReader.readLine()) != null) {
//$$                                       if (manifestLine.startsWith("Implementation-Version")) {
//$$                                           String[] manifestSplit = manifestLine.split(":");
//$$                                           if (manifestSplit.length > 1) {
//$$                                               version = manifestSplit[1].trim();
//$$                                           }
//$$                                       }
//$$                                   }
//$$                               }
//$$                           }
//$$
//$$                           return version;
//$$                       }
//$$                   }
//$$                }
//$$            }
//$$        } catch (ZipException ignored) {
//$$            return "UNKNOWN";
//$$       } catch (Exception e) {
//$$           LOGGER.error("Failed to get mod version from file: " + file.getFileName());
//$$           e.printStackTrace();
//$$       }
//$$
//$$       return "UNKNOWN";
//$$   }
//$$
//$$     public static String getEnvironmentType() {
//$$         Dist dist = FMLLoader.getDist();
//$$         if (dist.isClient()) {
//$$            return "CLIENT";
//$$        } else if (dist.isDedicatedServer()) {
//$$            return "SERVER";
//$$        } else {
//$$            return "UNKNOWN";
//$$         }
//$$     }
//$$ }
//#endif