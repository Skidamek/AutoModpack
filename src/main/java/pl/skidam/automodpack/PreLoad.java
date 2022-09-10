package pl.skidam.automodpack;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import pl.skidam.automodpack.client.DeleteTrashedMods;
import pl.skidam.automodpack.client.SelfUpdater;
import pl.skidam.automodpack.client.modpack.CheckModpack;
import pl.skidam.automodpack.client.modpack.DeleteMods;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.server.ServerSelfUpdater;
import pl.skidam.automodpack.utils.JarUtilities;
import pl.skidam.automodpack.utils.LoadModpackLink;
import pl.skidam.automodpack.utils.SetupFiles;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class PreLoad implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {

        LOGGER.info("Prelaunching AutoModpack...");

        JarUtilities.getJarFileOfMod("automodpack");

        modsPath = JarUtilities.getModsPath();

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {

            new SetupFiles();

            Config.init();

            new SelfUpdater(true);

            new LoadModpackLink();

//            if (!Config.DANGER_SCREEN) { // TODO make it better xD
                new CheckModpack(true);
//            }

            new DeleteTrashedMods();

            new CompatCheck();

            new DeleteMods(true, "false");

            LOGGER.info("AutoModpack successfully prelaunched!");
        }

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {

            new SetupFiles();

            Config.init();

            new ServerSelfUpdater();

            new CompatCheck();

            LOGGER.info("AutoModpack successfully prelaunched!");
        }
    }
}
