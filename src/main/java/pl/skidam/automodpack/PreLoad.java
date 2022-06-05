package pl.skidam.automodpack;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import pl.skidam.automodpack.Client.deletemods.DeleteMods;
import pl.skidam.automodpack.Client.deletemods.TrashMod;
import pl.skidam.automodpack.utils.SetupFiles;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class PreLoad implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {

        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }

        LOGGER.info("Prelaunching...");

        new SetupFiles();

        new TrashMod();

        while (true) {
            if (new File("./AutoModpack/TrashMod.jar").exists()) {
                break;
            }
        }

        new DeleteMods(true, "false");

        LOGGER.info("Successfully prelaunched!");
    }
}
