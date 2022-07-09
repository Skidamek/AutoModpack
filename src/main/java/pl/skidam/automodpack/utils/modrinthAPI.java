package pl.skidam.automodpack.utils;

import com.google.gson.JsonObject;

import net.minecraft.MinecraftVersion;
import pl.skidam.automodpack.AutoModpackMain;

public class modrinthAPI {
    
    public static String modrinthAPIdownloadUrl;
    public static String modrinthAPIlatest;
    public static String modrinthAPIfileName;
    public static long modrinthAPIsize;

    public static void modrinthAPI(String modrinthID) {

        AutoModpackMain.LOGGER.info(MinecraftVersion.CURRENT.getName());

        String url = "https://api.modrinth.com/v2/project/" + modrinthID + "/version?game_versions=[\"" + MinecraftVersion.CURRENT.getName() + "\"]";

        url = url.replaceAll("\"", "%22"); // so important!

        try {
            JsonObject JSONArray = new JsonTool().getJsonArray(url).get(0).getAsJsonObject();
            modrinthAPIlatest = JSONArray.get("version_number").getAsString();
            JsonObject JSONArrayfiles = JSONArray.getAsJsonArray("files").get(0).getAsJsonObject();
            modrinthAPIdownloadUrl = JSONArrayfiles.get("url").getAsString();
            modrinthAPIfileName = JSONArrayfiles.get("filename").getAsString();
            modrinthAPIsize = JSONArrayfiles.get("size").getAsLong();
        } catch (Exception e) {
            e.printStackTrace();
        }

        AutoModpackMain.LOGGER.warn("Latest version of AutoModpack is: " + modrinthAPIlatest);
        AutoModpackMain.LOGGER.warn("Url to download AutoModpack is: " + modrinthAPIdownloadUrl);
        AutoModpackMain.LOGGER.warn("File name of AutoModpack is: " + modrinthAPIfileName);
        AutoModpackMain.LOGGER.warn("Size of AutoModpack is: " + modrinthAPIsize);
    }
}
