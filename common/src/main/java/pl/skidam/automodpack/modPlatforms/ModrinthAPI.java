package pl.skidam.automodpack.modPlatforms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.utils.Json;

import static pl.skidam.automodpack.StaticVariables.LOGGER;
import static pl.skidam.automodpack.StaticVariables.MC_VERSION;

public class ModrinthAPI {

    private static final String BASE_URL = "https://api.modrinth.com/v2";

    public String requestUrl;
    public String downloadUrl;
    public String fileVersion;
    public String fileName;
    public long fileSize;
    public String releaseType;
    public String SHA512Hash;

    public ModrinthAPI(String requestUrl, String downloadUrl, String fileVersion, String fileName, long fileSize, String releaseType, String SHA512Hash) {
        this.requestUrl = requestUrl;
        this.downloadUrl = downloadUrl;
        this.fileVersion = fileVersion;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.releaseType = releaseType;
        this.SHA512Hash = SHA512Hash;
    }

    public static ModrinthAPI getModInfoFromID(String modrinthID) {

        String modLoader = Platform.getPlatformType().toString().toLowerCase();

        String requestUrl = BASE_URL + "/project/" + modrinthID + "/version?loaders=[\"" + modLoader + "\"]&game_versions=[\"" + MC_VERSION + "\"]";

        requestUrl = requestUrl.replaceAll("\"", "%22"); // so important!

        try {
            JsonArray JSONArray = Json.fromUrlAsArray(requestUrl);

            if (JSONArray == null) {
                LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
                return null;
            }

            JsonObject JSONObject = JSONArray.get(0).getAsJsonObject();

            String fileVersion = JSONObject.get("version_number").getAsString();
            String releaseType = JSONObject.get("version_type").getAsString();

            JsonObject JSONObjectFiles = JSONObject.getAsJsonArray("files").get(0).getAsJsonObject();

            String downloadUrl = JSONObjectFiles.get("url").getAsString();
            String fileName = JSONObjectFiles.get("filename").getAsString();
            long fileSize = JSONObjectFiles.get("size").getAsLong();
            String SHA512Hash = JSONObjectFiles.get("hashes").getAsJsonObject().get("sha512").getAsString();

            return new ModrinthAPI(requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, SHA512Hash);

        } catch (IndexOutOfBoundsException e) {
            LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static ModrinthAPI getModInfoFromSHA512(String sha512) {

        if (sha512 == null || sha512.isEmpty()) {
            return null;
        }

        String requestUrl = BASE_URL + "/version_file/" + sha512 + "?algorithm=sha512";

        requestUrl = requestUrl.replaceAll("\"", "%22"); // so important!

        try {
            JsonObject JSONObject = Json.fromUrl(requestUrl);

            if (JSONObject == null || JSONObject.size() == 0) {
                return null;
            }

            String fileVersion = JSONObject.get("version_number").getAsString();
            String releaseType = JSONObject.get("version_type").getAsString();

            JsonObject JSONObjectFiles = JSONObject.getAsJsonArray("files").get(0).getAsJsonObject();

            String downloadUrl = JSONObjectFiles.get("url").getAsString();
            String fileName = JSONObjectFiles.get("filename").getAsString();
            long fileSize = JSONObjectFiles.get("size").getAsLong();
            String SHA512Hash = JSONObjectFiles.get("hashes").getAsJsonObject().get("sha512").getAsString();

            return new ModrinthAPI(requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, SHA512Hash);

        } catch (IndexOutOfBoundsException e) {
            LOGGER.warn("Something gone wrong while getting info from Modrinth API: {}", requestUrl);
        } catch (Exception e) {
//            e.printStackTrace();
        }
        return null;
    }
}
