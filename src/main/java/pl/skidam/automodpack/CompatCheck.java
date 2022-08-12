package pl.skidam.automodpack;

import net.fabricmc.loader.api.FabricLoader;
import pl.skidam.automodpack.ui.ScreenBox;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.ModrinthAPI;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;
import static pl.skidam.automodpack.utils.ModrinthAPI.*;

public class CompatCheck {

    public CompatCheck() {

        if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
            AutoModpackMain.isClothConfig = true;
        }

        if (FabricLoader.getInstance().isModLoaded("modmenu")) {
            AutoModpackMain.isModMenu = true;
        }

        if (FabricLoader.getInstance().isModLoaded("fabricproxy-lite") || FabricLoader.getInstance().isModLoaded("crossstitch")) {
            AutoModpackMain.isVelocity = true;
        }

        if (FabricLoader.getInstance().isModLoaded("quilt_loader")) {
            AutoModpackMain.isQuiltLoader = true;
            // Download quilt api if we don't have it
            if (!FabricLoader.getInstance().isModLoaded("quilted_fabric_api")) { // QFAPI

                LOGGER.warn("Dependency (QFAPI) was not found");
                String modrinthID = "qvIfYCYJ"; // QFAPI ID
                new ModrinthAPI(modrinthID);
                LOGGER.info("Installing latest Quilted Fabric API (QFAPI)! " + modrinthAPIversion);
                LOGGER.info("Download URL: " + modrinthAPIdownloadUrl);
                if (Download.Download(modrinthAPIdownloadUrl, new File("./mods/" + modrinthAPIfileName))) { // Download it
                    LOGGER.info("Failed to download QFAPI!");
                    return;
                }
                LOGGER.info("Successfully installed latest Quilted Fabric API (QFAPI)!");

                new ScreenBox("Successfully installed latest Quilted Fabric API (QFAPI)!");
            }
        } else { // fabric
            AutoModpackMain.isFabricLoader = true;
            // Download fabric api if we don't have it
            if (!FabricLoader.getInstance().isModLoaded("fabric")) { // FAPI

                LOGGER.warn("Dependency (FAPI) was not found");
                String modrinthID = "P7dR8mSH"; // FAPI ID
                new ModrinthAPI(modrinthID);
                LOGGER.info("Installing latest Fabric API (FAPI)! " + modrinthAPIversion);
                LOGGER.info("Download URL: " + modrinthAPIdownloadUrl);
                if (Download.Download(modrinthAPIdownloadUrl, new File("./mods/" + modrinthAPIfileName))) { // Download it
                    LOGGER.info("Failed to download FAPI!");
                    return;
                }
                LOGGER.info("Successfully installed latest Fabric API (FAPI)!");

                new ScreenBox("Successfully installed latest Fabric API (FAPI)!");
            }
        }
    }
}
