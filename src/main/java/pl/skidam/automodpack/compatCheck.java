package pl.skidam.automodpack;

import net.fabricmc.loader.api.FabricLoader;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.modrinthAPI;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.ENV_BRAND;
import static pl.skidam.automodpack.AutoModpackMain.LOGGER;
import static pl.skidam.automodpack.utils.modrinthAPI.*;

public class compatCheck {


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
                modrinthAPI.modrinthAPI(modrinthID);
                LOGGER.info("Installing latest Fabric API (FAPI)! " + latest);
                LOGGER.error("Download URL: " + downloadUrl);
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
                modrinthAPI.modrinthAPI(modrinthID);
                LOGGER.error("Installing latest Quilted Fabric API (QFAPI)! " + latest);
                LOGGER.error("Download URL: " + downloadUrl);
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
}
