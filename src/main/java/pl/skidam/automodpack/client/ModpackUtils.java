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

package pl.skidam.automodpack.client;

import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.ModpackContentTools;

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
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

import static pl.skidam.automodpack.GlobalVariables.LOGGER;
import static pl.skidam.automodpack.GlobalVariables.AM_VERSION;
import static pl.skidam.automodpack.config.ConfigTools.GSON;

public class ModpackUtils {


    // If update to modpack found, returns true else false
    public static String isUpdate(Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        if (serverModpackContent == null || serverModpackContent.list == null) {
            LOGGER.error("Server modpack content list is null");
            return null;
        }

        // get client modpack content
        Path clientModpackContentFile = ModpackContentTools.getModpackContentFile(modpackDir);
        if (Objects.nonNull(clientModpackContentFile) && Files.exists(clientModpackContentFile)) {

            Jsons.ModpackContentFields clientModpackContent = ConfigTools.loadConfig(clientModpackContentFile, Jsons.ModpackContentFields.class);

            if (clientModpackContent == null) {
                return "true";
            }

            if (clientModpackContent.modpackHash == null) {
                LOGGER.error("Modpack hash is null");
                return "true";
            }

            if (clientModpackContent.modpackHash.equals(serverModpackContent.modpackHash)) {
                LOGGER.info("Modpack hash is the same as server modpack hash");
                return "false";
            }

            else {
                LOGGER.info("Modpack hash is different than server modpack hash");
                return "true";
            }
        } else {
            return "true";
        }
    }

    public static void copyModpackFilesFromModpackDirToRunDir(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, List<String> ignoreFiles) throws IOException {
        if (serverModpackContent == null || serverModpackContent.list == null) {
            LOGGER.error("Server modpack content list is null");
            return;
        }

        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : serverModpackContent.list) {
            String fileName = contentItem.file;

            if (ignoreFiles.contains(fileName)) {
                continue;
            }

            Path sourceFile = Paths.get(modpackDir + fileName);

            if (Files.exists(sourceFile)) {
                Path destinationFile = Paths.get("." + fileName);

                if (Files.exists(destinationFile)) {
                    try {
                        if (CustomFileUtils.compareFileHashes(sourceFile, destinationFile, "SHA-1")) {
                            continue;
                        }
                    } catch (NoSuchAlgorithmException e) {
                        e.printStackTrace();
                    }
                }

                CustomFileUtils.copyFile(sourceFile, destinationFile);
            } else {
                LOGGER.error("File " + fileName + " doesn't exist in modpack directory!?");
            }
        }
    }


    public static void copyModpackFilesFromRunDirToModpackDir(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, List<String> ignoreFiles) throws Exception {
        List<Jsons.ModpackContentFields.ModpackContentItem> contents = serverModpackContent.list;

        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : contents) {

            if (ignoreFiles.contains(contentItem.file)) {
                continue;
            }

            Path sourceFile = Paths.get("." + contentItem.file);

            if (Files.exists(sourceFile)) {

                // check hash
                String serverHash = contentItem.sha1;
                String localHash = CustomFileUtils.getHash(sourceFile, "SHA-1");

                if (!serverHash.equals(localHash) && !contentItem.editable) {
                    continue;
                }

                Path destinationFile = Paths.get(modpackDir + File.separator + contentItem.file);

                CustomFileUtils.copyFile(sourceFile, destinationFile);
            }
        }
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
