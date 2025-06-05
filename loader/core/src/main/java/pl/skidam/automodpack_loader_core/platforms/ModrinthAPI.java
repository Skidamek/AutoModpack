package pl.skidam.automodpack_loader_core.platforms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pl.skidam.automodpack_core.utils.Json;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public record ModrinthAPI(String modrinthID, String requestUrl, String downloadUrl, String fileVersion, String fileName, long fileSize, String releaseType, String SHA1Hash) {

    private static final String BASE_URL = "https://api.modrinth.com/v2";


    public static List<ModrinthAPI> getModInfosFromID(String modrinthID) {

        String modLoader = LOADER_MANAGER.getPlatformType().toString().toLowerCase();

        String requestUrl = BASE_URL + "/project/" + modrinthID + "/version?loaders=[\"" + modLoader + "\"]&game_versions=[\"" + MC_VERSION + "\"]";

        requestUrl = requestUrl.replaceAll("\"", "%22"); // so important!

        List<ModrinthAPI> modrinthAPIList = new ArrayList<>();

        try {
            JsonArray JSONArray = Json.fromUrlAsArray(requestUrl);

            if (JSONArray == null) {
                LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
                return null;
            }

            for (JsonElement jsonElement : JSONArray) {
                JsonObject JSONObject = jsonElement.getAsJsonObject();

                String fileVersion = JSONObject.get("version_number").getAsString();
                String releaseType = JSONObject.get("version_type").getAsString();

                JsonObject JSONObjectFiles = JSONObject.getAsJsonArray("files").get(0).getAsJsonObject();

                String downloadUrl = JSONObjectFiles.get("url").getAsString();
                String fileName = JSONObjectFiles.get("filename").getAsString();
                long fileSize = JSONObjectFiles.get("size").getAsLong();
                String SHA1Hash = JSONObjectFiles.get("hashes").getAsJsonObject().get("sha1").getAsString();

                modrinthAPIList.add(new ModrinthAPI(modrinthID, requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, SHA1Hash));
            }
        } catch (IndexOutOfBoundsException e) {
            LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return modrinthAPIList;
    }


    public static ModrinthAPI getModSpecificVersion(String modrinthID, String modVersion, String mcVersion) {

        String modLoader = LOADER_MANAGER.getPlatformType().toString().toLowerCase();

        String requestUrl = BASE_URL + "/project/" + modrinthID + "/version?loaders=[\"" + modLoader + "\"]&game_versions=[\"" + mcVersion + "\"]";

        requestUrl = requestUrl.replaceAll("\"", "%22"); // important!

        try {
            // get all versions
            JsonArray JSONArray = Json.fromUrlAsArray(requestUrl);

            if (JSONArray == null) {
                LOGGER.warn("Can't find mod for your client, tried link " + requestUrl);
                return null;
            }

            for (JsonElement jsonElement : JSONArray) {
                JsonObject JSONObject = jsonElement.getAsJsonObject();

                String fileVersion = JSONObject.get("version_number").getAsString();

                if (fileVersion.equals(modVersion)) {
                    String releaseType = JSONObject.get("version_type").getAsString();

                    JsonObject JSONObjectFiles = JSONObject.getAsJsonArray("files").get(0).getAsJsonObject();

                    String downloadUrl = JSONObjectFiles.get("url").getAsString();
                    String fileName = JSONObjectFiles.get("filename").getAsString();
                    long fileSize = JSONObjectFiles.get("size").getAsLong();
                    String SHA1Hash = JSONObjectFiles.get("hashes").getAsJsonObject().get("sha1").getAsString();

                    return new ModrinthAPI(modrinthID, requestUrl, downloadUrl, fileVersion, fileName, fileSize, releaseType, SHA1Hash);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;

    }

    // https://docs.modrinth.com/#tag/version-files/operation/versionsFromHashes
    public static List<ModrinthAPI> getModsInfosFromListOfSHA1(List<String> listOfSha1) {
        if (listOfSha1 == null || listOfSha1.isEmpty()) {
            return null;
        }

        String requestUrl = BASE_URL + "/version_files";
        List<ModrinthAPI> modrinthAPIList = new LinkedList<>();

        try {
            JsonObject JSONObjects = Json.fromModrinthUrl(requestUrl, listOfSha1);
            for (String key : JSONObjects.keySet()) {
                JsonObject JSONObject = JSONObjects.getAsJsonObject(key);
                ModrinthAPI modrinthAPI = parseJsonObject(JSONObject, listOfSha1);
                if (modrinthAPI != null) modrinthAPIList.add(modrinthAPI);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to fetch data from Modrinth API", e);
        }

        return modrinthAPIList;
    }

    private static ModrinthAPI parseJsonObject(JsonObject JSONObject, List<String> listOfSha1) {
        if (JSONObject == null) {
            return null;
        }

        String modrinthID = JSONObject.get("project_id").getAsString();

        String fileVersion = JSONObject.get("version_number").getAsString();
        String releaseType = JSONObject.get("version_type").getAsString();

        JsonArray filesArray = JSONObject.getAsJsonArray("files");
        JsonObject JSONObjectFile = null;

        String sha1 = listOfSha1.size() == 1 ? listOfSha1.get(0) : null;

        // some projects can have more than one file under the same version
        for (JsonElement fileElement : filesArray) {
            JsonObject fileObject = fileElement.getAsJsonObject();
            JsonObject hashesObject = fileObject.getAsJsonObject("hashes");
            String sha1Hash = hashesObject.get("sha1").getAsString();

            if (sha1 != null && sha1.equals(sha1Hash)) {
                JSONObjectFile = fileObject;
                break;
            } else if (listOfSha1.contains(sha1Hash)) {
                JSONObjectFile = fileObject;
                break;
            }
        }

        if (JSONObjectFile == null) {
            if (sha1 != null) LOGGER.error("Can't find file with SHA1 hash: " + sha1);
            return null;
        }

        String downloadUrl = JSONObjectFile.get("url").getAsString();
        String fileName = JSONObjectFile.get("filename").getAsString();
        long fileSize = JSONObjectFile.get("size").getAsLong();
        if (sha1 == null) sha1 = JSONObjectFile.get("hashes").getAsJsonObject().get("sha1").getAsString();

        return new ModrinthAPI(modrinthID, null, downloadUrl, fileVersion, fileName, fileSize, releaseType, sha1);
    }

    public static String getMainPageUrl(String modrinthID, String fileType) {
        return "https://modrinth.com/" + fileType + "/" + modrinthID;
    }
}
