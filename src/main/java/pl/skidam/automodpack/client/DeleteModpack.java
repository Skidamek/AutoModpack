package pl.skidam.automodpack.client;

import org.apache.commons.io.FileDeleteStrategy;
import pl.skidam.automodpack.utils.ShityCompressor;
import pl.skidam.automodpack.utils.ShityDeCompressor;

import java.io.File;
import java.io.FileWriter;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class DeleteModpack {
    public DeleteModpack() {

        LOGGER.warn("Deleting modpack...");

        // unzip modpack.zip
        new ShityDeCompressor(new File("./AutoModpack/modpack.zip"), new File("./AutoModpack/modpack/"), true, "none");

        // MODS
        // make array of file names "./AutoModpack/modpack/mods/" folder
        File[] modpackModsFiles = new File("./AutoModpack/modpack/mods/").listFiles();

        // loop to delete all names in ./mods/ folder of names in files in "./AutoModpack/modpack/mods/"
        for (File modpackModName : modpackModsFiles) {

            String modName = modpackModName.getName();
            File modFile = new File("./mods/" + modName);

            if (modFile.exists()) {
                try {
                    if (modFile.exists()) {
                        LOGGER.info("Deleting: " + modName);
                        FileDeleteStrategy.FORCE.delete(modFile);
                    }
                } catch (Exception e) { // ignore
                }
                if (modFile.exists()) { // if mod to delete still exists
                    // MAGIC TACTIC
                    new ShityCompressor(new File("./AutoModpack/TrashMod/"), modFile);
                    LOGGER.info("Successfully converted to TrashMod: " + modFile);
                    // add name of mod to the txt file in ./AutoModpack/trashed-mods.txt
                    try {
                        FileWriter fw = new FileWriter("./AutoModpack/trashed-mods.txt", true);
                        fw.write(modName + "\n");
                        fw.close();
                    } catch (Exception e) { // ignore
                    }
                }
            }
        }

        // CONFIGS
        // make array of file names "./AutoModpack/modpack/config/" folder
        File[] modpackConfigFiles = new File("./AutoModpack/modpack/config/").listFiles();

        // loop to delete all names in ./config/ folder of names in files in "./AutoModpack/modpack/config/"
        for (File modpackConfigName : modpackConfigFiles) {

            String configName = modpackConfigName.getName();
            File configFile = new File("./config/" + configName);

            if (configFile.exists()) {
                try {
                    if (configFile.exists()) {
                        LOGGER.info("Deleting: " + configName);
                        FileDeleteStrategy.FORCE.delete(configFile);
                    }
                    LOGGER.info("Successfully deleted: " + configName);
                } catch (Exception e) {
                    LOGGER.error("Error while deleting: " + configName);
                    LOGGER.error(e.getMessage());
                }
            }
        }

        // delete unzipped modpack dir, modpack.zip and modpack-link.txt
        try {
            FileDeleteStrategy.FORCE.delete(new File("./AutoModpack/modpack/"));
            FileDeleteStrategy.FORCE.delete(new File("./AutoModpack/modpack.zip"));
            FileDeleteStrategy.FORCE.delete(new File("./AutoModpack/modpack-link.txt"));
        } catch (Exception e) { // ignore it
        }

        LOGGER.info("Finished deleting modpack!");
        LOGGER.info("Restart your game!");
    }
}
