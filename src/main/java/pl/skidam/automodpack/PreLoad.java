package pl.skidam.automodpack;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import pl.skidam.automodpack.deletemods.DeleteMods;
import pl.skidam.automodpack.deletemods.TrashMod;
import pl.skidam.automodpack.utils.SetupFiles;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class PreLoad implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {

        // Check if mod is loaded on client. I know it's bad, but I don't know how to do it better
        File serverProperties = new File("./server.properties");
        if (serverProperties.exists()) {
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
