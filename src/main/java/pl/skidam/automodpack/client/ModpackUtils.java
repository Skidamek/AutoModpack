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

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.MinecraftUserName;
import pl.skidam.automodpack.utils.ModpackContentTools;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import static pl.skidam.automodpack.GlobalVariables.LOGGER;
import static pl.skidam.automodpack.GlobalVariables.VERSION;
import static pl.skidam.automodpack.config.ConfigTools.GSON;

public class ModpackUtils {


    // If update to modpack found, returns true else false
    public static Boolean isUpdate(Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        if (serverModpackContent == null || serverModpackContent.list == null) {
            LOGGER.error("Server modpack content list is null");
            return false;
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

    public static void copyModpackFilesFromModpackDirToRunDir(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, List<String> ignoreFiles) throws IOException {
        List<Jsons.ModpackContentFields.ModpackContentItem> contents = serverModpackContent.list;

        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : contents) {
            String fileName = contentItem.file;

            if (ignoreFiles.contains(fileName)) {
                continue;
            }

            Path sourceFile = Paths.get(modpackDir + File.separator + fileName);

            if (Files.exists(sourceFile)) {
                Path destinationFile = Paths.get("." + fileName);

//                if (destinationFile.exists()) {
//                    CustomFileUtils.forceDelete(destinationFile, false);
//                }

                CustomFileUtils.copyFile(sourceFile, destinationFile);
//                LOGGER.info("Copied " + fileName + " to running directory");
            }
        }
    }


    public static void copyModpackFilesFromRunDirToModpackDir(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, List<String> ignoreFiles) throws Exception {
        List<Jsons.ModpackContentFields.ModpackContentItem> contents = serverModpackContent.list;

        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : contents) {

            if (ignoreFiles.contains(contentItem.file)) {
                continue;
            }

            Path sourceFile = Paths.get("./" + contentItem.file);

            if (Files.exists(sourceFile)) {

                // check hash
                String serverHash = contentItem.sha1;
                String localHash = CustomFileUtils.getHash(sourceFile, "SHA-1");

                if (!serverHash.equals(localHash) && !contentItem.editable) {
                    continue;
                }

                Path destinationFile = Paths.get(modpackDir + File.separator + contentItem.file);

//                if (destinationFile.exists()) {
//                    CustomFileUtils.forceDelete(destinationFile, false);
//                }

                CustomFileUtils.copyFile(sourceFile, destinationFile);
//                LOGGER.info("Copied " + sourceFile.getName() + " to modpack directory");
            }
        }
    }

    public static Jsons.ModpackContentFields getServerModpackContent(String link) {

        if (link == null) {
            return null;
        }

        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(3000)
                        .setSocketTimeout(3000)
                        .build())
                .build()) {

            HttpGet getContent = new HttpGet(link);
            getContent.addHeader("Content-Type", "application/json");
            getContent.addHeader("Minecraft-Username", MinecraftUserName.get());
            getContent.addHeader("User-Agent", "github/skidamek/automodpack/" + VERSION);

            HttpResponse response = httpClient.execute(getContent);
            HttpEntity entity = response.getEntity();
            String contentResponse = EntityUtils.toString(entity, StandardCharsets.UTF_8);

            Jsons.ModpackContentFields serverModpackContent = GSON.fromJson(contentResponse, Jsons.ModpackContentFields.class);

            if (serverModpackContent.list.size() < 1) {
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

            getContent.releaseConnection();

            return serverModpackContent;
        } catch (ConnectException e) {
            LOGGER.error("Couldn't connect to modpack server " + link);
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content");
            e.printStackTrace();
        }
        return null;
    }
}
