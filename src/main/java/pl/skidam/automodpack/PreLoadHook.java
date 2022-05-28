package pl.skidam.automodpack;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import pl.skidam.automodpack.deletemods.DeleteMods;
import pl.skidam.automodpack.deletemods.TrashMod;
import pl.skidam.automodpack.utils.SetupFiles;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class PreLoadHook implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {

        // check if mod is loaded on client
        File serverProperties = new File("./server.properties");
        if (serverProperties.exists()) {
            return;
        }

        LOGGER.info("PreLaunching...");

        new SetupFiles();

        new TrashMod();

        while (true) {
            if (new File("./AutoModpack/TrashMod.jar").exists()) {
                break;
            }
        }

        new DeleteMods(true, "false");

        LOGGER.info("Successfully preLaunched!");

    }
}
