package pl.skidam.automodpack_loader_core.platforms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pl.skidam.automodpack_core.utils.Json;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public record CurseForgeAPI(String requestUrl, String downloadUrl, String fileVersion, String fileName, String fileSize, String releaseType, String murmurHash, String sha1Hash) {

    public static final String BASE_URL = "https://api.curseforge.com/v1";

    // key - sha1, value - murmur
    // https://docs.curseforge.com/?java#get-fingerprints-matches
    public static List<CurseForgeAPI> getModInfosFromFingerPrints(Map<String, String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            return null;
        }

        String requestUrl = BASE_URL + "/fingerprints";
        List<CurseForgeAPI> curseForgeAPIList = new LinkedList<>();
        
        try {
            JsonArray exactMatches = Json.fromCurseForgeUrl(requestUrl, hashes.values().stream().toList()).get("data").getAsJsonObject().get("exactMatches").getAsJsonArray();
            for (JsonElement match : exactMatches) {
                JsonObject JSONObject = match.getAsJsonObject();
                CurseForgeAPI curseForgeAPI = parseJsonObject(JSONObject, hashes);
                if (curseForgeAPI != null) curseForgeAPIList.add(curseForgeAPI);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to fetch data from CurseForge API", e);
        }

        return curseForgeAPIList;
    }

//    public static void main(String[] args) {
//        var ww = getModInfosFromFingerPrints(Map.of("2ff512a70c437c20523de01ea95b6fc9b164a5c0", "197497879"));
//        ww.forEach(System.out::println);
//    }

    public static CurseForgeAPI parseJsonObject(JsonObject JSONObject, Map<String, String> hashes) {
        if (JSONObject == null) {
            return null;
        }

        JsonObject fileJson = JSONObject.get("file").getAsJsonObject();

        // https://docs.curseforge.com/?java#tocS_FileReleaseType
        int releaseTypeInt = fileJson.get("releaseType").getAsInt();
        String releaseType = switch (releaseTypeInt) {
            case 1 -> "release";
            case 2 -> "beta";
            case 3 -> "alpha";
            default -> null;
        };

        JsonArray fileHashes = fileJson.getAsJsonArray("hashes");

        String sha1 = hashes.size() == 1 ? hashes.keySet().stream().findFirst().get() : null;
        boolean found = false;

        for (JsonElement hashElement : fileHashes) {
            JsonObject hashObject = hashElement.getAsJsonObject();
            // sha1 - https://docs.curseforge.com/?java#tocS_FileHash
            if (hashObject.get("algo").getAsInt() == 1) {
                var hash = hashObject.get("value").getAsString();
                if (sha1 != null && sha1.equals(hash)) {
                    found = true;
                    break;
                } else if (hashes.containsKey(hash)) {
                    sha1 = hash;
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            if (sha1 != null) LOGGER.error("Can't find file with SHA1 hash: " + sha1);
            return null;
        }

        String downloadUrl = fileJson.get("downloadUrl").getAsString();
        String fileName = fileJson.get("fileName").getAsString();
        String fileVersion = fileJson.get("displayName").getAsString();
        String fileSize = String.valueOf(fileJson.get("fileLength").getAsLong());
        String murmur = hashes.get(sha1);

        return new CurseForgeAPI(null, downloadUrl, fileVersion, fileName, fileSize, releaseType, murmur, sha1);
    }

}
