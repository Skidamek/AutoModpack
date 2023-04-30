package pl.skidam.automodpack.platforms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import pl.skidam.automodpack.utils.Json;

import java.io.IOException;

import static pl.skidam.automodpack.StaticVariables.LOGGER;

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

    public static CurseForgeAPI getModInfoFromMurmur(String murmur) {
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

            return new CurseForgeAPI(null, downloadUrl, fileVersion, fileName, fileSize, releaseType, murmurHash);

        } catch (IOException e) {
            LOGGER.error("Can't connect to CurseForge API");
            e.printStackTrace();
        } catch (Exception ignored) {

        }
        return null;
    }
}
