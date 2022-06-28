package pl.skidam.automodpack.client.modpack;

import org.apache.commons.io.FileDeleteStrategy;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.utils.ShityCompressor;
import pl.skidam.automodpack.utils.ShityDeCompressor;
import pl.skidam.automodpack.utils.Wait;

import java.io.*;
import java.util.Scanner;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class DeleteMods {
    File delModsTxt = new File("./delmods.txt");
    boolean preload;
    String ModpackUpdated;

    public DeleteMods(boolean preload, String ModpackUpdated) {

        this.ModpackUpdated = ModpackUpdated;

        if (preload) {
            this.preload = true;
            Wait.wait(500);
            if (!delModsTxt.exists()) {
                new ShityDeCompressor(new File("./AutoModpack/modpack.zip"), new File("./"), false, "delmods.txt");
            }
        }
        if (!preload) {
            this.preload = false;
        }
        Delete();
    }

    private void Delete() {

        if (!delModsTxt.exists() || delModsTxt.length() == 0) {
            if (preload) {
                return;
            }
            AutoModpackMain.ModpackUpdated = this.ModpackUpdated;
        }

        try {
            FileReader fr = new FileReader(delModsTxt);
            Scanner inFile = new Scanner(fr);

            // loop to delete all names in ./mods/ folder of names in files in delmods.txt
            while (inFile.hasNextLine()) {

                String modName = inFile.nextLine();
                File modFile = new File("./mods/" + modName);

                if (modFile.exists()) {

                    if (!modName.endsWith(".jar")) {
                        modFile = new File("./mods/" + modName + ".jar");
                    }

                    if (modFile.exists()) {
                        LOGGER.info("Deleting: " + modName);
                        try {
                            FileDeleteStrategy.FORCE.delete(modFile);
                        } catch (IOException ignored) {
                        }
                    }

                    if (modFile.exists()) { // if mod to delete still exists
                        try {
                            new ShityCompressor(new File("./AutoModpack/TrashMod/"), modFile);
                        } catch (IOException ignored) {
                        }
                        try {
                            FileWriter fw = new FileWriter("./AutoModpack/trashed-mods.txt", true);
                            fw.write(modName + "\n");
                            fw.close();
                        } catch (IOException ignored) {
                        }
                    }

                    if (modFile.exists()) {
                        try {
                            FileDeleteStrategy.FORCE.delete(modFile);
                        } catch (IOException ignored) {
                        }
                    }

                    if (!modFile.exists()) {
                        LOGGER.info("Successfully deleted: " + modName);
                    } else if (modFile.exists() && modFile.length() == 16681) {
                        LOGGER.info("Successfully trashed: " + modName);
                    } else {
                        LOGGER.info("Failed to delete: " + modName);
                    }
                }
            }

            // Close the file
            inFile.close();
        } catch (IOException ignored) { }

        // Delete the file
        try {
            FileDeleteStrategy.FORCE.delete(delModsTxt);
        } catch (IOException ignored) { }


        LOGGER.info("Finished deleting mods!");

        if (!preload) {
            AutoModpackMain.ModpackUpdated = this.ModpackUpdated;
        }
    }
}