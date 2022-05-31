package pl.skidam.automodpack.Client.deletemods;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FileDeleteStrategy;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.utils.ShityCompressor;
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
                try {
                    new ZipFile("./AutoModpack/modpack.zip").extractFile("delmods.txt", "./");
                    new ZipFile("./AutoModpack/modpack.zip").extractFile("delmods", "./");
                } catch (ZipException e) { // ignore it
                }
            }
        }
        if (!preload) {
            this.preload = false;
        }
        Delete();
    }

    private void Delete() {

        if (!delModsTxt.exists()) { return; };
        if (delModsTxt.length() == 0) { return; };

        try {
            FileReader fr = new FileReader(delModsTxt);
            Scanner inFile = new Scanner(fr);

            // loop to delete all names in ./mods/ folder of names in files in delmods.txt
            while (inFile.hasNextLine()) {

                String modNameLine = inFile.nextLine();
                File modName = new File("./mods/" + modNameLine);

                if (modName.exists()) {
                    if (!modNameLine.endsWith(".jar")) {
                        modName = new File("./mods/" + modNameLine + ".jar");
                    }

                    if (modName.exists()) {
                        LOGGER.info("Deleting: " + modNameLine);
                        FileDeleteStrategy.FORCE.delete(modName);
                    }

                    if (modName.exists()) { // if mod to delete still exists
                        // MAGIC TACTIC
                        new ShityCompressor(new File("./AutoModpack/TrashMod/"), modName);
                        LOGGER.warn("Successfully converted to TrashMod: " + modName);
                    }

                    if (modName.exists()) { // if mod to delete still exists
                        // Try to delete it again
                        FileDeleteStrategy.FORCE.delete(modName);
                    }

                    LOGGER.info("Successfully deleted: " + modNameLine);
                }
            }

            // Close the file
            inFile.close();

            // Delete the file
            FileDeleteStrategy.FORCE.delete(delModsTxt);

        } catch (IOException e) { // ignore it
        }

        LOGGER.info("Finished deleting mods!");

        if (!preload) {
            AutoModpackMain.ModpackUpdated = this.ModpackUpdated;
        }
    }
}