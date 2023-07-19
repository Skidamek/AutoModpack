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

package pl.skidam.automodpack.platforms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pl.skidam.automodpack.loaders.Loader;
import pl.skidam.automodpack.utils.Json;

import static pl.skidam.automodpack.GlobalVariables.LOGGER;
import static pl.skidam.automodpack.GlobalVariables.MC_VERSION;

public class ModrinthAPI {

    private static final String BASE_URL = "https://api.modrinth.com/v2";

    public String modrinthID;
    public String requestUrl;
    public String downloadUrl;
    public String fileVersion;
    public String fileName;
    public long fileSize;
    public String releaseType;
    public String SHA1Hash;

    public ModrinthAPI(String modrinthID, String requestUrl, String downloadUrl, String fileVersion, String fileName, long fileSize, String releaseType, String SHA1Hash) {
        this.modrinthID = modrinthID;
        this.requestUrl = requestUrl;
        this.downloadUrl = downloadUrl;
        this.fileVersion = fileVersion;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.releaseType = releaseType;
        this.SHA1Hash = SHA1Hash;
    }

    public static ModrinthAPI getModInfoFromID(String modrinthID) {

        String modLoader = Loader.getPlatformType().toString().toLowerCase();

        String requestUrl = BASE_URL + "/project/" + modrinthID + "/version?loaders=[\"" + modLoader + "\"]&game_versions=[\"" + MC_VERSION + "\"]";

        requestUrl = requestUrl.replaceAll("\"", "%22"); // so important!

        try {
            JsonArray JSONArray = Json.fromUrlAsArray(requestUrl);

            if (JSONArray == null) {
                LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
                return null;
            }

            JsonObject JSONObject = JSONArray.get(0).getAsJsonObject();

            String fileVersion = JSONObject.get("version_number").getAsString();
            String releaseType = JSONObject.get("version_type").getAsString();

            JsonObject JSONObjectFiles = JSONObject.getAsJsonArray("files").get(0).getAsJsonObject();

            String downloadUrl = JSONObjectFiles.get("url").getAsString();
            String fileName = JSONObjectFiles.get("filename").getAsString();
            long fileSize = JSONObjectFiles.get("size").getAsLong();
            String SHA1Hash = JSONObjectFiles.get("hashes").getAsJsonObject().get("sha1").getAsString();

            return new ModrinthAPI(modrinthID, requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, SHA1Hash);

        } catch (IndexOutOfBoundsException e) {
            LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static ModrinthAPI getModSpecificVersion(String modrinthID, String modVersion, String mcVersion) {

        String modLoader = Loader.getPlatformType().toString().toLowerCase();

        String requestUrl = BASE_URL + "/project/" + modrinthID + "/version?loaders=[\"" + modLoader + "\"]&game_versions=[\"" + mcVersion + "\"]";

        requestUrl = requestUrl.replaceAll("\"", "%22"); // so important!

        try {
            // get all versions
            JsonArray JSONArray = Json.fromUrlAsArray(requestUrl);

            if (JSONArray == null) {
                LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
                return null;
            }

            for (JsonElement jsonElement : JSONArray) {
                JsonObject JSONObject = jsonElement.getAsJsonObject();

                String fileVersion = JSONObject.get("version_number").getAsString();

                if (fileVersion.equals(modVersion)) {
                    String releaseType = JSONObject.get("version_type").getAsString();

                    JsonObject JSONObjectFiles = JSONObject.getAsJsonArray("files").get(0).getAsJsonObject();

                    String downloadUrl = JSONObjectFiles.get("url").getAsString();
                    String fileName = JSONObjectFiles.get("filename").getAsString();
                    long fileSize = JSONObjectFiles.get("size").getAsLong();
                    String SHA1Hash = JSONObjectFiles.get("hashes").getAsJsonObject().get("sha1").getAsString();

                    return new ModrinthAPI(modrinthID, requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, SHA1Hash);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;

    }

    public static ModrinthAPI getModInfoFromSHA512(String sha1) {

        if (sha1 == null || sha1.isEmpty()) {
            return null;
        }

        String requestUrl = BASE_URL + "/version_file/" + sha1 + "?algorithm=sha1";

        requestUrl = requestUrl.replaceAll("\"", "%22"); // so important!

        try {
            JsonObject JSONObject = Json.fromUrl(requestUrl);

            if (JSONObject == null || JSONObject.size() == 0) {
                return null;
            }

            String modrinthID = JSONObject.get("project_id").getAsString();

            String fileVersion = JSONObject.get("version_number").getAsString();
            String releaseType = JSONObject.get("version_type").getAsString();

            JsonArray filesArray = JSONObject.getAsJsonArray("files");
            JsonObject JSONObjectFile = null;


            // some projects can have more than one file under the same version
            if (filesArray.size() == 1) {
                JSONObjectFile = filesArray.get(0).getAsJsonObject();
            } else {
                for (JsonElement fileElement : filesArray) {
                    JsonObject fileObject = fileElement.getAsJsonObject();
                    JsonObject hashesObject = fileObject.getAsJsonObject("hashes");
                    String sha1Hash = hashesObject.get("sha1").getAsString();

                    if (sha1Hash.equals(sha1)) {
                        JSONObjectFile = fileObject;
                        break;
                    }
                }
            }

            if (JSONObjectFile == null) {
                LOGGER.error("Can't find file with SHA1 hash: {}", sha1);
                return null;
            }

            String downloadUrl = JSONObjectFile.get("url").getAsString();
            String fileName = JSONObjectFile.get("filename").getAsString();
            long fileSize = JSONObjectFile.get("size").getAsLong();
            String SHA512Hash = JSONObjectFile.get("hashes").getAsJsonObject().get("sha1").getAsString();

            return new ModrinthAPI(modrinthID, requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, SHA512Hash);

        } catch (IndexOutOfBoundsException e) {
            LOGGER.warn("Something gone wrong while getting info from Modrinth API: {}", requestUrl);
        } catch (Exception e) {
//            e.printStackTrace();
        }
        return null;
    }

    public static String getMainPageUrl(String modrinthID, String fileType) {
        return "https://modrinth.com/" + fileType + "/" + modrinthID;
    }
}
