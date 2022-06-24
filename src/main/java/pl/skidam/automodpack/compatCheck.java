package pl.skidam.automodpack;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.JsonTools;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.ENV_BRAND;
import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class compatCheck {

    private static String downloadUrl = "";
    private static String latest = "";
    private static String fileName = "";

    public compatCheck() {

        if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
            AutoModpackMain.isClothConfig = true;
        }

        if (FabricLoader.getInstance().isModLoaded("modmenu")) {
            AutoModpackMain.isModMenu = true;
        }

        if (ENV_BRAND.equals("fabric")) {
            // Download fabric api if we don't have it
            if (!FabricLoader.getInstance().isModLoaded("fabric")) { // FAPI

                LOGGER.warn("Dependency (FAPI) was not found");
                String modrinthID = "P7dR8mSH"; // FAPI ID
                getLatestFile(modrinthID);
                LOGGER.info("Installing latest Fabric API (FAPI)!");
                if (Download.Download(downloadUrl, new File("./mods/" + fileName))) { // Download it
                    LOGGER.info("Failed to download FAPI!");
                    return;
                }
                LOGGER.info("Successfully installed latest Fabric API (FAPI)!");

                // TODO make this crash better
                throw new RuntimeException("Successfully installed latest Fabric API (FAPI)!");
            }
        }

        if (ENV_BRAND.equals("quilt")) {
            // Download quilt api if we don't have it
            if (!FabricLoader.getInstance().isModLoaded("quilted_fabric_api")) { // QFAPI

                LOGGER.warn("Dependency (QFAPI) was not found");
                String modrinthID = "qvIfYCYJ"; // QFAPI ID
                getLatestFile(modrinthID);
                LOGGER.info("Installing latest Quilted Fabric API (QFAPI)!");
                if (Download.Download(downloadUrl, new File("./mods/" + fileName))) { // Download it
                    LOGGER.info("Failed to download QFAPI!");
                    return;
                }
                LOGGER.info("Successfully installed latest Quilted Fabric API (QFAPI)!");

                // TODO make this crash better
                throw new RuntimeException("Successfully installed latest Quilted Fabric API (QFAPI)!");
            }
        }
    }

    private void getLatestFile(String ID) {

        String version = "1.19";

        String url = "https://api.modrinth.com/v2/project/" + ID + "/version?loaders=[" + ENV_BRAND + "]&game_versions=[" + version + "]";

        try {
            JsonObject release = new JsonTools().getJsonArray(url).get(0).getAsJsonObject();
            latest = release.get("version_number").getAsString();
            JsonObject releaseDownload = release.getAsJsonArray("files").get(0).getAsJsonObject();
            fileName = releaseDownload.get("filename").getAsString();
            downloadUrl = releaseDownload.get("url").getAsString();
        }catch (Exception e) {
        }
    }
}
