package pl.skidam.automodpack.utils;

import com.google.gson.JsonObject;
import pl.skidam.automodpack.Platform;

import static pl.skidam.automodpack.StaticVariables.*;

public class ModrinthAPI {

    public String modrinthAPIrequestUrl;
    public String modrinthAPIdownloadUrl;
    public String modrinthAPIversion;
    public String modrinthAPIfileName;
    public long modrinthAPIsize;
    public String modrinthAPIversionType;
    public String modrinthAPISHA512Hash;

    public ModrinthAPI(String modrinthID) {

        String url = "https://api.modrinth.com/v2/project/" + modrinthID + "/version?loaders=[\"" + Platform.getPlatformType().toString().toLowerCase() + "\"]&game_versions=[\"" + MC_VERSION + "\"]";

        url = url.replaceAll("\"", "%22"); // so important!

        try {
            this.modrinthAPIrequestUrl = url;

            JsonObject JSONArray = Json.fromUrlAsArray(url).get(0).getAsJsonObject();

            this.modrinthAPIversion = JSONArray.get("version_number").getAsString();
            this.modrinthAPIversionType = JSONArray.get("version_type").getAsString();

            JsonObject JSONArrayFiles = JSONArray.getAsJsonArray("files").get(0).getAsJsonObject();

            this.modrinthAPIdownloadUrl = JSONArrayFiles.get("url").getAsString();
            this.modrinthAPIfileName = JSONArrayFiles.get("filename").getAsString();
            this.modrinthAPIsize = JSONArrayFiles.get("size").getAsLong();
            this.modrinthAPISHA512Hash = JSONArrayFiles.get("hashes").getAsJsonObject().get("sha512").getAsString();

        } catch (IndexOutOfBoundsException e) {
            LOGGER.warn("Can't find mod for your client, tried link " + url);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
