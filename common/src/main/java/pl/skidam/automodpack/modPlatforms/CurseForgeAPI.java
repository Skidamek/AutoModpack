package pl.skidam.automodpack.modPlatforms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static pl.skidam.automodpack.StaticVariables.LOGGER;

public class CurseForgeAPI {

    private static final String BASE_URL = "https://api.curseforge.com/v1";
    private static final String key = "";
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

    public static CurseForgeAPI getModInfoFromMurmur(String murmur) {

        if (murmur == null || murmur.isEmpty()) {
            return null;
        }

        String url = BASE_URL + "/project/" + murmur;


        String body = "{\n" +
                "  \"fingerprints\": [\n" +
                "    " + murmur + "\n" +
                "  ]\n" +
                "}";

        try {
            HttpURLConnection connection;
            URL requestUrl = new URL(BASE_URL + "/fingerprints");
            connection = (HttpURLConnection) requestUrl.openConnection();
            connection.setDoOutput(true);
            connection.addRequestProperty("Accept", "application/json");
            connection.addRequestProperty("x-api-key", key);
            connection.addRequestProperty("Content-Type", "application/json");
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

            StringBuilder content;
            BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            content = new StringBuilder();
            while ((line = br.readLine()) != null) {
                content.append(line);
            }
            connection.disconnect();

            JsonObject JSONObject = JsonParser.parseString(content.toString()).getAsJsonObject();

            if (JSONObject == null || JSONObject.size() == 0) { // it always response something
                LOGGER.warn("CurseForge API is down");
                return null;
            }

            JsonObject dataObject = JSONObject.getAsJsonObject("data");
            JsonArray exactMatchesArray = dataObject.getAsJsonArray("exactMatches");
            JsonObject fileObject = exactMatchesArray.get(0).getAsJsonObject().getAsJsonObject("file");


            String fileVersion = null;
            String releaseType = null;

            String downloadUrl = fileObject.get("downloadUrl").getAsString();
            String fileName = fileObject.get("fileName").getAsString();
            long fileSize = fileObject.get("fileLength").getAsLong();
            String murmurHash = fileObject.get("fileFingerprint").getAsString();

            return new CurseForgeAPI(url, downloadUrl, fileVersion, fileName, fileSize, releaseType, murmurHash);

        } catch (IOException e) {
//            LOGGER.error("Can't connect to CurseForge API, tried: {}", url);
//            e.printStackTrace();
        }
        return null;
    }
}
