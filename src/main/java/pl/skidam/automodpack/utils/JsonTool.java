package pl.skidam.automodpack.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class JsonTool {

    public JsonArray getJsonArray(String url) throws IOException {
        JsonElement element = null;

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("X-Minecraft-Username", "other-packet");
        connection.setConnectTimeout(3000); // 3 seconds
        connection.setReadTimeout(3000); // 3 seconds as well
        connection.connect();
        if (connection.getResponseCode() == 200) {
            try (InputStreamReader isr = new InputStreamReader(connection.getInputStream())) {
                element = JsonParser.parseReader(isr);
            }
        }

        connection.disconnect();

        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        return null;
    }
}