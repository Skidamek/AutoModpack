package pl.skidam.automodpack;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import pl.skidam.automodpack.client.DeleteTrashedMods;
import pl.skidam.automodpack.client.SelfUpdater;
import pl.skidam.automodpack.client.modpack.DeleteMods;
import pl.skidam.automodpack.client.modpack.TrashMod;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.server.ServerSelfUpdater;
import pl.skidam.automodpack.utils.InternetConnectionCheck;
import pl.skidam.automodpack.utils.SetupFiles;

import java.io.File;
import java.util.Objects;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class PreLoad implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {

        LOGGER.info("Prelaunching AutoModpack...");

        // check if AutoModpack has correct name
        File mods = new File("./mods/");
        String[] modsList = mods.list();

        for (String mod : Objects.requireNonNull(modsList)) {
            if (mod.endsWith(".jar")) {
                File modFile = new File("./mods/" + mod);
                if (mod.toLowerCase().contains("automodpack") && !mod.equals(correctName)) {
                    selfOut = modFile; // save current name
                }
            }
        }

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {

            new SetupFiles();

            Config.init();

            if (InternetConnectionCheck.InternetConnectionCheck()) {
                new SelfUpdater(true);
            }

            new TrashMod();

            new DeleteTrashedMods();

            new CompatCheck();

            new DeleteMods(true, "false");

            LOGGER.info("AutoModpack successfully prelaunched!");
        }

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {

            new SetupFiles();

            Config.init();

            if (InternetConnectionCheck.InternetConnectionCheck()) {
                new ServerSelfUpdater();
            }

            new CompatCheck();

            LOGGER.info("AutoModpack successfully prelaunched!");
        }
    }
}