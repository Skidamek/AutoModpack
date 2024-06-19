package pl.skidam.automodpack_core.utils;

import com.google.gson.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static pl.skidam.automodpack_core.GlobalVariables.AM_VERSION;
import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

@SuppressWarnings("deprecation")
public class Json {
    public static JsonArray fromUrlAsArray(String url) {
        JsonElement element = null;

        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.connect();
            if (connection.getResponseCode() == 200) {
                try (InputStreamReader isr = new InputStreamReader(connection.getInputStream())) {
                    JsonParser parser = new JsonParser(); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
                    element = parser.parse(isr);
                }
            }
            connection.disconnect();
        } catch (SocketTimeoutException ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        return null;
    }

    public static JsonObject fromFile(Path path) throws IOException {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return null;
        }

        JsonParser parser = new JsonParser();
        byte[] bytes = Files.readAllBytes(path);

        StringBuilder sb = new StringBuilder();
        for (Byte b : bytes) {
            sb.append((char) b.byteValue());
        }

        return parser.parse(sb.toString()).getAsJsonObject();
    }

    public static JsonObject fromUrl(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.connect();

        JsonElement element = null;

        int code = connection.getResponseCode();
        if (code == 200) {
            try (InputStreamReader isr = new InputStreamReader(connection.getInputStream())) {
                element = new JsonParser().parse(isr); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
            }
        } else {
            LOGGER.warn("{} responded {} code", url, code);
        }

        connection.disconnect();

        if (element != null && !element.isJsonArray()) {
            return element.getAsJsonObject();
        }

        return null;
    }

    public static JsonObject fromModrinthUrl(final String requestUrl, List<String> listOfSha1) throws IOException {
        if (listOfSha1 == null || listOfSha1.isEmpty()) {
            return null;
        }

        JsonObject jsonObject = new JsonObject();
        jsonObject.add("hashes", new Gson().toJsonTree(listOfSha1));
        jsonObject.addProperty("algorithm", "sha1");

        final String body = jsonObject.toString();

        HttpURLConnection connection;
        URL url = new URL(requestUrl);
        connection = (HttpURLConnection) url.openConnection();
        connection.addRequestProperty("Content-Type", "application/json");
        connection.addRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        connection.connect();

        JsonElement element = null;

        int code = connection.getResponseCode();
        if (code == 200) {
            try (InputStreamReader isr = new InputStreamReader(connection.getInputStream())) {
                element = new JsonParser().parse(isr); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
            }
        } else {
            LOGGER.warn("{} responded {} code", url, code);
        }

        connection.disconnect();

        if (element != null && !element.isJsonArray()) {
            return element.getAsJsonObject();
        }

        return null;

    }

    public static JsonObject fromCurseForgeUrl(final String requestUrl, List<String> listOfMurmur) throws IOException {

        if (listOfMurmur == null || listOfMurmur.isEmpty()) {
            return null;
        }

        JsonObject jsonObject = new JsonObject();
        Gson gson = new Gson().newBuilder().setPrettyPrinting().create();
        jsonObject.add("fingerprints", gson.toJsonTree(listOfMurmur));

        final String body = jsonObject.toString();

        HttpURLConnection connection;
        URL url = new URL(requestUrl);
        connection = (HttpURLConnection) url.openConnection();
        connection.addRequestProperty("Content-Type", "application/json");
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("x-api-key", "$2a$10$skl4d4Y2MR6c.nZhV3uVK.GBeKd3ML4RKyMns8DZqj91Hjf/HYrcS");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        connection.connect();

        JsonElement element = null;

        int code = connection.getResponseCode();
        if (code == 200) {
            try (InputStreamReader isr = new InputStreamReader(connection.getInputStream())) {
                element = new JsonParser().parse(isr); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
            }
        } else {
            LOGGER.warn("{} responded {} code", url, code);
        }

        connection.disconnect();

        if (element != null && !element.isJsonArray()) {
            return element.getAsJsonObject();
        }

        return null;
    }
}