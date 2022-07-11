package pl.skidam.automodpack.utils;

import com.google.gson.JsonObject;

import net.minecraft.MinecraftVersion;

public class ModrinthAPI {

    public static String modrinthAPIdownloadUrl;
    public static String modrinthAPIversion;
    public static String modrinthAPIfileName;
    public static long modrinthAPIsize;

    public ModrinthAPI(String modrinthID) {

        String url = "https://api.modrinth.com/v2/project/" + modrinthID + "/version?game_versions=[\"" + MinecraftVersion.CURRENT.getName() + "\"]";

        url = url.replaceAll("\"", "%22"); // so important!

        try {
            JsonObject JSONArray = new JsonTool().getJsonArray(url).get(0).getAsJsonObject();
            modrinthAPIversion = JSONArray.get("version_number").getAsString();
            JsonObject JSONArrayfiles = JSONArray.getAsJsonArray("files").get(0).getAsJsonObject();
            modrinthAPIdownloadUrl = JSONArrayfiles.get("url").getAsString();
            modrinthAPIfileName = JSONArrayfiles.get("filename").getAsString();
            modrinthAPIsize = JSONArrayfiles.get("size").getAsLong();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}