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
import java.util.List;
import java.util.Random;

public class JsonTools { // By Osiris-Team

    /**
     * Returns the json-element. This can be a json-array or a json-object.
     *
     * @param input_url The url which leads to the json file.
     * @return JsonElement
     * @throws Exception When status code other than 200.
     */
    public JsonElement getJsonElement(String input_url) throws IOException {

        HttpURLConnection con = null;
        JsonElement element = null;
        try {
            con = (HttpURLConnection) new URL(input_url).openConnection();
            con.addRequestProperty("User-Agent", "AutoPlug - https://autoplug.online - Request-ID: " + new Random().nextInt());
            con.setConnectTimeout(1000);
            con.connect();

            if (con.getResponseCode() == 200) {
                try (InputStreamReader inr = new InputStreamReader(con.getInputStream())) {
                    element = JsonParser.parseReader(inr);
                }
            }
        } catch (IOException e) {
            if (con != null) con.disconnect();
            throw e;
        } finally {
            if (con != null) con.disconnect();
        }
        return element;
    }

    public JsonArray getJsonArray(String url) throws IOException {
        JsonElement element = getJsonElement(url);
        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        return null;
    }

    /**
     * Turns a JsonArray with its objects into a list.
     *
     * @param url The url where to find the json file.
     * @return A list with JsonObjects or null if there was a error with the url.
     */
    public List<JsonObject> getJsonArrayAsList(String url) throws IOException {
        List<JsonObject> objectList = new ArrayList<>();
        JsonElement element = getJsonElement(url);
        if (element != null && element.isJsonArray()) {
            final JsonArray ja = element.getAsJsonArray();
            for (int i = 0; i < ja.size(); i++) {
                JsonObject jo = ja.get(i).getAsJsonObject();
                objectList.add(jo);
            }
            return objectList;
        }
        return objectList;
    }

    /**
     * Gets a single JsonObject.
     *
     * @param url The url where to find the json file.
     * @return A JsonObject or null if there was a error with the url.
     */
    public JsonObject getJsonObject(String url) throws IOException {
        JsonElement element = getJsonElement(url);
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        return null;
    }

}