package pl.skidam.automodpack.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

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

    public static ArrayList<JsonObject> fromUrlAsList(String url) {
        ArrayList<JsonObject> ArrayList = new ArrayList<>();
        JsonElement element = fromUrlAsArray(url);

        if (element != null && element.isJsonArray()) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject jsonObject = jsonArray.get(i).getAsJsonObject();
                ArrayList.add(jsonObject);
            }
            return ArrayList;
        }

        return null;
    }
}