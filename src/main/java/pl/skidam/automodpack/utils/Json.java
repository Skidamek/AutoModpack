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
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static pl.skidam.automodpack.StaticVariables.VERSION;

public class Json {
    public static JsonArray fromUrlAsArray(String url) {
        JsonElement element = null;

        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-Agent", "github/skidamek/automodpack/" + VERSION);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setDoOutput(true);
            connection.connect();
            if (connection.getResponseCode() == 200) {
                try (InputStreamReader isr = new InputStreamReader(connection.getInputStream())) {
                    JsonParser parser = new JsonParser(); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
                    element = parser.parse(isr);
                }
            }
            connection.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        return null;
    }

    public static JsonObject fromUrl(String url) throws IOException {
        JsonElement element = null;

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", "github/skidamek/automodpack/" + VERSION);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setDoOutput(true);
        connection.connect();
        if (connection.getResponseCode() == 200) {
            try (InputStreamReader isr = new InputStreamReader(connection.getInputStream())) {
                JsonParser parser = new JsonParser(); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
                element = parser.parse(isr);
            }
        }
        connection.disconnect();

        if (element != null && !element.isJsonArray()) {
            return element.getAsJsonObject();
        }
        return null;
    }

    public static JsonObject fromUrlCurseForge(String murmurHash) throws IOException {

        if (murmurHash == null || murmurHash.isEmpty()) {
            return null;
        }

        final String BASE_URL = "https://api.curseforge.com/v1";

        String body = "{\n" +
                "  \"fingerprints\": [\n" +
                "    " + murmurHash + "\n" +
                "  ]\n" +
                "}";

        HttpURLConnection connection;
        URL requestUrl = new URL(BASE_URL + "/fingerprints");
        connection = (HttpURLConnection) requestUrl.openConnection();
        connection.setDoOutput(true);
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("x-api-key", "$2a$10$skl4d4Y2MR6c.nZhV3uVK.GBeKd3ML4RKyMns8DZqj91Hjf/HYrcS");
        connection.addRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setDoOutput(true);

        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        StringBuilder content;
        BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String line;
        content = new StringBuilder();
        while ((line = br.readLine()) != null) {
            content.append(line);
        }
        connection.disconnect();

        return new JsonParser().parse(content.toString()).getAsJsonObject(); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
    }
}