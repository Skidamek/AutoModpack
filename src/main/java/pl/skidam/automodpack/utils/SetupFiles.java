package pl.skidam.automodpack.utils;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class SetupFiles {
    public SetupFiles() {

        File AMdir = new File("./AutoModpack/");
        // Check if AutoModpack path exists
        if (!AMdir.exists()) {
            AMdir.mkdir();
        }

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            server();
            return;
        }
        client();

    }

    private void server() {

        File modsDir = new File("./AutoModpack/modpack/");
        if (!modsDir.exists()) {
            modsDir.mkdir();
        }
    }

    private void client() {

        File oldAM = new File("./AutoModpack/OldAutoModpack/");
        if (!oldAM.exists()) {
            oldAM.mkdir();
        }

        // Auto renaming system. Rename the wrong name of automodpack mod to the right name.
        File AutoModpackJar = new File( "./mods/AutoModpack.jar");
        File mods = new File("./mods/");
        String[] modsList = mods.list();

        for (String mod : modsList) {
            if (mod.endsWith(".jar")) {
                File modFile = new File("./mods/" + mod);
                if (mod.toLowerCase().contains("automodpack") && !mod.equalsIgnoreCase("AutoModpack.jar")) {
                    LOGGER.warn("Renaming " + modFile + " to AutoModpack.jar");
                    try {
                        Files.move(modFile.toPath(), AutoModpackJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) { // ignore it
                    }
                }
            }
        }
    }
}
