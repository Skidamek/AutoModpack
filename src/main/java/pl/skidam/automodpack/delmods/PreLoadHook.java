package pl.skidam.automodpack.delmods;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public final class PreLoadHook implements PreLaunchEntrypoint {
public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");

    @Override
    public void onPreLaunch() {

        LOGGER.info("PreLaunching AutoModpack...");

        new Thread(new TrashMod()).start();

        while (true) {
            if (new File("./AutoModpack/TrashMod.jar").exists()) {
                break;
            }
        }

        new Thread(new DeleteMods(true)).start();
    }
}
