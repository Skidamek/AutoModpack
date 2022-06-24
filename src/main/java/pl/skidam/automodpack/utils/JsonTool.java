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

        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(1000);
        con.connect();
        if (con.getResponseCode() == 200) {
            try (InputStreamReader inr = new InputStreamReader(con.getInputStream())) {
                element = JsonParser.parseReader(inr);
            }
        }

        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        return null;
    }
}