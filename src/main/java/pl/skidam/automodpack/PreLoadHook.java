package pl.skidam.automodpack;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import pl.skidam.automodpack.delmods.DeleteMods;
import pl.skidam.automodpack.delmods.TrashMod;
import pl.skidam.automodpack.utils.SetupFiles;

import java.io.File;

public class PreLoadHook implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {

        // check if mod is loaded on client
        File serverProperties = new File("./server.properties");
        if (serverProperties.exists()) {
            return;
        }

        AutoModpackClient.LOGGER.info("AutoModpack -- PreLaunching...");

        new SetupFiles();

        new TrashMod();

        while (true) {
            if (new File("./AutoModpack/TrashMod.jar").exists()) {
                break;
            }
        }

        new DeleteMods(true, "false");

    }
}
