package pl.skidam.automodpack;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import pl.skidam.automodpack.client.DeleteTrashedMods;
import pl.skidam.automodpack.client.modpack.DeleteMods;
import pl.skidam.automodpack.client.modpack.TrashMod;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.utils.InternetConnectionCheck;
import pl.skidam.automodpack.utils.SetupFiles;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class PreLoadQuilt implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {

        LOGGER.info("Prelaunching AutoModpack...");

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            Config.init();

            new TrashMod();

            while (true) {
                if (new File("./AutoModpack/TrashMod.jar").exists()) {
                    break;
                }
            }

            new DeleteTrashedMods();

            InternetConnectionCheck.InternetConnectionCheck();

            // quilt loader detected
            ENV_BRAND = "quilt";

            new compatCheck();

            new SetupFiles();

            new DeleteMods(true, "false");

            LOGGER.info("AutoModpack successfully prelaunched!");
        }

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            Config.init();

            InternetConnectionCheck.InternetConnectionCheck();

            // quilt loader detected
            ENV_BRAND = "quilt";

            new compatCheck();

            new SetupFiles();

            LOGGER.info("AutoModpack successfully prelaunched!");
        }
    }
}