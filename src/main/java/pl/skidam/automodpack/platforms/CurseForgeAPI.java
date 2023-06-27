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
import com.google.gson.JsonObject;
import pl.skidam.automodpack.utils.Json;

import java.io.IOException;

import static pl.skidam.automodpack.GlobalVariables.LOGGER;

public class CurseForgeAPI {
    public String requestUrl;
    public String downloadUrl;
    public String fileVersion;
    public String fileName;
    public long fileSize;
    public String releaseType;
    public String murmurHash;

    public CurseForgeAPI(String requestUrl, String downloadUrl, String fileVersion, String fileName, long fileSize, String releaseType, String murmurHash) {
        this.requestUrl = requestUrl;
        this.downloadUrl = downloadUrl;
        this.fileVersion = fileVersion;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.releaseType = releaseType;
        this.murmurHash = murmurHash;
    }

    public static CurseForgeAPI getModInfoFromMurmur(String murmur, String serverFileName) {
        try {
            JsonObject JSONObject = Json.fromUrlCurseForge(murmur);

            if (JSONObject == null || JSONObject.size() == 0) { // it always response something
                LOGGER.warn("CurseForge API is down");
                return null;
            }

            JsonObject dataObject = JSONObject.getAsJsonObject("data");
            JsonArray exactMatchesArray = dataObject.getAsJsonArray("exactMatches");
            JsonObject fileObject = exactMatchesArray.get(0).getAsJsonObject().getAsJsonObject("file");

            if (fileObject == null) {
                return null;
            }

            String fileVersion = null;
            String releaseType = null;

            if (fileObject.get("downloadUrl").isJsonNull()) {
                return null;
            }

            String downloadUrl = fileObject.get("downloadUrl").getAsString();
            String fileName = fileObject.get("fileName").getAsString();
            long fileSize = fileObject.get("fileLength").getAsLong();
            String murmurHash = fileObject.get("fileFingerprint").getAsString();

            if (!serverFileName.equals(fileName)) {
                return null;
            }

            return new CurseForgeAPI(null, downloadUrl, fileVersion, fileName, fileSize, releaseType, murmurHash);

        } catch (IOException e) {
            LOGGER.error("Can't connect to CurseForge API");
            e.printStackTrace();
        } catch (Exception ignored) {

        }
        return null;
    }
}
