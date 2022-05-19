package pl.skidam.automodpack.delmods;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import pl.skidam.automodpack.AutoModpackClient;
import pl.skidam.automodpack.utils.OldConvertToNew;

import java.io.File;

@Environment(EnvType.CLIENT)
public class PreLoadHook implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {

        AutoModpackClient.LOGGER.info("AutoModpack -- PreLaunching...");

        new OldConvertToNew();

        new TrashMod();

        while (true) {
            if (new File("./AutoModpack/TrashMod.jar").exists()) {
                break;
            }
        }

        new DeleteMods(true, "false");
    }
}
