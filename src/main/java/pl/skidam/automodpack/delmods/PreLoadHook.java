package pl.skidam.automodpack.delmods;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.OldConvertToNew;

import java.io.File;

public class PreLoadHook implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {

        AutoModpack.LOGGER.info("AutoModpack -- PreLaunching...");

        new Thread(new OldConvertToNew()).start();

        new Thread(new TrashMod()).start();

        while (true) {
            if (new File("./AutoModpack/TrashMod.jar").exists()) {
                break;
            }
        }

        new Thread(new DeleteMods(true)).start();
    }
}
