package pl.skidam.automodpack;

import net.fabricmc.loader.api.FabricLoader;
import pl.skidam.automodpack.client.DeleteModpack;
import pl.skidam.automodpack.ui.ScreenBox;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.JarUtilities;
import pl.skidam.automodpack.utils.ModrinthAPI;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;
import static pl.skidam.automodpack.AutoModpackMain.modsPath;
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

        if (FabricLoader.getInstance().isModLoaded("seamless_loading_screen")) {
            String jarName = JarUtilities.getJarFileOfMod("seamless_loading_screen");
            LOGGER.error("Found incompatibility between AutoModpack and Seamless Loading Screen. Delete {} mod to have better effect!", jarName);
            LOGGER.error("Found incompatibility between AutoModpack and Seamless Loading Screen. Delete {} mod to have better effect!", jarName);
            LOGGER.error("Found incompatibility between AutoModpack and Seamless Loading Screen. Delete {} mod to have better effect!", jarName);
        }

        if (FabricLoader.getInstance().isModLoaded("quilt_loader")) {
            AutoModpackMain.isQuiltLoader = true;

            // search mod folder to delete fapi if exists on quilt loader
            File[] files = modsPath.toFile().listFiles();
            assert files != null;
            for (File file : files) {
                if (file.getName().toLowerCase().startsWith("fabric-api-")) {
                    DeleteModpack.deleteLogic(file);
                    DeleteModpack.deleted = true; // reset deleted boolean to dont broke DeleteModpack class
                }
            }

            // Download quilt api if we don't have it
            if (!FabricLoader.getInstance().isModLoaded("quilted_fabric_api")) { // QFAPI

                LOGGER.warn("Dependency (QFAPI) was not found");
                String modrinthID = "qvIfYCYJ"; // QFAPI ID
                new ModrinthAPI(modrinthID);
                LOGGER.info("Installing latest Quilted Fabric API (QFAPI)! " + modrinthAPIversion);
                LOGGER.info("Download URL: " + modrinthAPIdownloadUrl);
                if (Download.Download(modrinthAPIdownloadUrl, new File(modsPath.toFile() + File.separator + modrinthAPIfileName))) { // Download it
                    LOGGER.info("Failed to download QFAPI!");
                    return;
                }
                LOGGER.info("Successfully installed latest Quilted Fabric API (QFAPI)!");

                new ScreenBox("Successfully installed Quilted Fabric API (QFAPI)!");
            }
        } else { // fabric or something other lol
            AutoModpackMain.isFabricLoader = true;

            // search mod folder to delete fapi if exists on quilt loader
            File[] files = modsPath.toFile().listFiles();
            assert files != null;
            for (File file : files) {
                if (file.getName().toLowerCase().startsWith("qfapi-")) {
                    DeleteModpack.deleteLogic(file);
                    DeleteModpack.deleted = true; // reset deleted boolean to dont broke DeleteModpack class
                }
            }

            // Download fabric api if we don't have it
            if (!FabricLoader.getInstance().isModLoaded("fabric-api") && !FabricLoader.getInstance().isModLoaded("fabric")) { // FAPI

                LOGGER.warn("Dependency (FAPI) was not found");
                String modrinthID = "P7dR8mSH"; // FAPI ID
                new ModrinthAPI(modrinthID);
                LOGGER.info("Installing latest Fabric API (FAPI)! " + modrinthAPIversion);
                LOGGER.info("Download URL: " + modrinthAPIdownloadUrl);
                if (Download.Download(modrinthAPIdownloadUrl, new File(modsPath.toFile() + File.separator + modrinthAPIfileName))) { // Download it
                    LOGGER.info("Failed to download FAPI!");
                    return;
                }
                LOGGER.info("Successfully installed latest Fabric API (FAPI)!");

                new ScreenBox("Successfully installed Fabric API (FAPI)!");
            }
        }
    }
}
