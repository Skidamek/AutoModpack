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

package pl.skidam.automodpack_core.client;

import pl.skidam.automodpack_common.config.ConfigTools;
import pl.skidam.automodpack_common.config.Jsons;
import pl.skidam.automodpack_common.utils.CustomFileUtils;
import pl.skidam.automodpack_common.utils.FileInspection;
import pl.skidam.automodpack_common.utils.ModpackContentTools;
import pl.skidam.automodpack_common.utils.Url;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

import static pl.skidam.automodpack_common.config.ConfigTools.GSON;
import static pl.skidam.automodpack_common.GlobalVariables.*;

public class ModpackUtils {


    // If update to modpack found, returns true else false
    public static Boolean isUpdate(Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        if (serverModpackContent == null || serverModpackContent.list == null) {
            LOGGER.error("Server modpack content list is null");
            return null;
        }

        // get client modpack content
        Path clientModpackContentFile = ModpackContentTools.getModpackContentFile(modpackDir);
        if (Objects.nonNull(clientModpackContentFile) && Files.exists(clientModpackContentFile)) {

            Jsons.ModpackContentFields clientModpackContent = ConfigTools.loadConfig(clientModpackContentFile, Jsons.ModpackContentFields.class);

            if (clientModpackContent == null) {
                return true;
            }

            if (clientModpackContent.modpackHash == null) {
                LOGGER.error("Modpack hash is null");
                return true;
            }

            if (clientModpackContent.modpackHash.equals(serverModpackContent.modpackHash)) {
                LOGGER.info("Modpack hash is the same as server modpack hash");
                return false;
            }

            else {
                LOGGER.info("Modpack hash is different than server modpack hash");
                return true;
            }
        } else {
            return true;
        }
    }

    public static void correctFilesLocations(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, List<String> ignoreFiles) throws IOException {
        if (serverModpackContent == null || serverModpackContent.list == null) {
            LOGGER.error("Server modpack content list is null");
            return;
        }

        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : serverModpackContent.list) {
            String file = contentItem.file;

            if (ignoreFiles.contains(file)) {
                continue;
            }

            if (contentItem.type != null && contentItem.type.equals("mod")) {
                continue;
            }

            Path modpackFile = Paths.get(modpackDir + file);
            Path runFile = Paths.get("." + file);

            if (Files.exists(modpackFile)) {
                CustomFileUtils.copyFile(modpackFile, runFile);
            } else if (Files.exists(runFile)) {
                CustomFileUtils.copyFile(runFile, modpackFile);
            } else {
                LOGGER.error("File " + file + " doesn't exist!?");
            }
        }
    }


    public static List<Path> renameModpackDir(Path modpackContentFile, Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        Jsons.ModpackContentFields clientModpackContent = ConfigTools.loadModpackContent(modpackContentFile);
        if (clientModpackContent != null) {
            String installedModpackName = clientModpackContent.modpackName;
            String serverModpackName = serverModpackContent.modpackName;

            if (!serverModpackName.equals(installedModpackName) && !serverModpackName.isEmpty()) {

                Path newModpackDir = Path.of(modpackDir.getParent() + File.separator + serverModpackName);

                try {
                    Files.move(modpackDir, newModpackDir, StandardCopyOption.REPLACE_EXISTING);

                    // TODO remove old modpack from list
                    addModpackToList(newModpackDir.getFileName().toString());
                    selectModpack(newModpackDir);

                    LOGGER.info("Changed modpack name of {} to {}", modpackDir.getFileName().toString(), serverModpackName);

                    modpackContentFile = Path.of(newModpackDir + File.separator + modpackContentFile.getFileName());

                    return List.of(newModpackDir, modpackContentFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return null;
    }

    public static void selectModpack(Path modpackDir) {
        clientConfig.selectedModpack = modpackDir.getFileName().toString();
        ConfigTools.saveConfig(clientConfigFile, clientConfig);
    }

    public static void addModpackToList(String modpackName) {
        if (modpackName == null || modpackName.isEmpty()) {
            return;
        }

        if (clientConfig.installedModpacks == null) {
            clientConfig.installedModpacks = List.of(modpackName);
        } else if (!clientConfig.installedModpacks.contains(modpackName)) {
            clientConfig.installedModpacks.add(modpackName);
        }

        ConfigTools.saveConfig(clientConfigFile, clientConfig);
    }

    // Returns modpack name formatted for path or url if server doesn't provide modpack name
    public static Path getModpackPath(String url, String modpackName) {

        String nameFromUrl = Url.removeHttpPrefix(url);

        if (FileInspection.isInValidFileName(nameFromUrl)) {
            nameFromUrl = FileInspection.fixFileName(nameFromUrl);
        }

        Path modpackDir = Path.of(modpacksDir + File.separator + nameFromUrl);

        if (!modpackName.isEmpty()) {
            // Check if we don't have already installed modpack via this link
            if (clientConfig.installedModpacks != null && clientConfig.installedModpacks.contains(nameFromUrl)) {
                return modpackDir;
            }

            String nameFromName = modpackName;

            if (FileInspection.isInValidFileName(modpackName)) {
                nameFromName = FileInspection.fixFileName(modpackName);
            }

            modpackDir = Path.of(modpacksDir + File.separator + nameFromName);
        }

        return modpackDir;
    }

    public static Jsons.ModpackContentFields getServerModpackContent(String link) {
        if (link == null) {
            return null;
        }

        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(link).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(3000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Minecraft-Username", "");
            connection.setRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);

            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String contentResponse = response.toString();

                Jsons.ModpackContentFields serverModpackContent = GSON.fromJson(contentResponse, Jsons.ModpackContentFields.class);

                if (serverModpackContent == null) {
                    LOGGER.error("Couldn't connect to modpack server " + link);
                    return null;
                }

                if (serverModpackContent.list.isEmpty()) {
                    LOGGER.error("Modpack content is empty!");
                    return null;
                }

                // check if modpackContent is valid/isn't malicious
                for (Jsons.ModpackContentFields.ModpackContentItem modpackContentItem : serverModpackContent.list) {
                    String file = modpackContentItem.file.replace("\\", "/");
                    String url = modpackContentItem.link.replace("\\", "/");
                    if (file.contains("/../") || url.contains("/../")) {
                        LOGGER.error("Modpack content is invalid, it contains /../ in file name or url");
                        return null;
                    }
                }

                return serverModpackContent;
            } else {
                LOGGER.error("Couldn't connect to modpack server " + link + ", Response Code: " + responseCode);
            }
        } catch (ConnectException | SocketTimeoutException e) {
            LOGGER.error("Couldn't connect to modpack server " + link);
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content");
            e.printStackTrace();
        }

        return null;
    }
}
