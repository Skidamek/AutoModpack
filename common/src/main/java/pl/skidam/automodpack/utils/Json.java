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
                    element = JsonParser.parseReader(isr);
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
                element = JsonParser.parseReader(isr);
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
        connection.addRequestProperty("x-api-key", "$2a$10$BR7v3YyA40YR404c8ePNfuFqDBYUz5mkmgbOCLbhlA2zGeup5mjFG");
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

        return JsonParser.parseString(content.toString()).getAsJsonObject();
    }
}