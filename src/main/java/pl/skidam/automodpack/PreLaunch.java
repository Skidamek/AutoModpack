package pl.skidam.automodpack;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.apache.commons.io.FileDeleteStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;

public final class PreLaunch implements PreLaunchEntrypoint {

public static final Logger LOGGER = LoggerFactory.getLogger("PreLaunched - AutoModpack");
    @Override
    public void onPreLaunch() {
        LOGGER.info("AutoModpack -- deleting mods...");

        // Delete old mods by deleting the folder
        File delMods = new File("./delmods/");
        String[] oldModsList = delMods.list();
        if (delMods.exists()) {
            assert oldModsList != null;
            for (String name : oldModsList) {
                File oldMod = new File("./mods/" + name);
                if (name.endsWith(".jar") && !name.equals("AutoModpack.jar") && oldMod.exists()) {
                    LOGGER.info("AutoModpack -- Deleting: " + name);
                    try {
                        FileReader fr = new FileReader(oldMod);
                        Scanner inFile = new Scanner(fr);

                        // Unload mod from modloader to delete it

                        inFile.close();

                        FileDeleteStrategy.FORCE.delete(oldMod);
                    } catch (IOException e) {
                        LOGGER.error(String.valueOf(e));
                    }

                    LOGGER.info("AutoModpack -- Successfully deleted: " + name);
                }
            }
        }

        LOGGER.info("AutoModpack -- Successfully preloaded");

    }
}
