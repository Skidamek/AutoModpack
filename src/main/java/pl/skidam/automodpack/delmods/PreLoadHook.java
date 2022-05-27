package pl.skidam.automodpack.delmods;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import pl.skidam.automodpack.AutoModpackClient;
import pl.skidam.automodpack.utils.CreateFiles;

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

        new CreateFiles();

        new TrashMod();

        while (true) {
            if (new File("./AutoModpack/TrashMod.jar").exists()) {
                break;
            }
        }

        new DeleteMods(true, "false");

    }
}
