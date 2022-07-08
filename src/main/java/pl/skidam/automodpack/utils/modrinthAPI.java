package pl.skidam.automodpack.utils;

import com.google.gson.JsonObject;

import net.minecraft.MinecraftVersion;

public class modrinthAPI {
    
    public static String downloadUrl = "";
    public static String latest = "";
    public static String fileName = "";

    public static void modrinthAPI(String modrinthID) {

        String url = "https://api.modrinth.com/v2/project/" + modrinthID + "/version?game_versions=[\"" + MinecraftVersion.CURRENT + "\"]";

        url = url.replaceAll("\"", "%22"); // so important!

        try {
            JsonObject release = new JsonTool().getJsonArray(url).get(0).getAsJsonObject();
            latest = release.get("version_number").getAsString();
            JsonObject releaseDownload = release.getAsJsonArray("files").get(0).getAsJsonObject();
            downloadUrl = releaseDownload.get("url").getAsString();
            fileName = releaseDownload.get("filename").getAsString();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
