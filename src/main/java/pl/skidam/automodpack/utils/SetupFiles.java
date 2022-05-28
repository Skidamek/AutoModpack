package pl.skidam.automodpack.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class SetupFiles {
    public SetupFiles() {

        File AMdir = new File("./AutoModpack/");
        // check if AutoModpack path exists
        if (!AMdir.exists()) {
            AMdir.mkdir();
        }

        // check if mod is loaded on client
        File serverProperties = new File("./server.properties");
        if (serverProperties.exists()) {
            server();
            return;
        }
        client();
    }

    private void server() {


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
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

    }
}
