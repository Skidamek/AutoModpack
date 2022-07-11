package pl.skidam.automodpack.client.modpack;

import org.apache.commons.io.FileDeleteStrategy;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.utils.UnZipper;
import pl.skidam.automodpack.utils.Zipper;
import pl.skidam.automodpack.utils.Wait;

import java.io.*;
import java.util.Scanner;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;
import static pl.skidam.automodpack.AutoModpackMain.out;

public class DeleteMods {
    private static File delModsTxt = new File("./delmods.txt");
    private static boolean preload;
    private static String ModpackUpdated;
    private static boolean modsDeleted;

    public DeleteMods(boolean preload, String ModpackUpdated) {

        DeleteMods.ModpackUpdated = ModpackUpdated;
        DeleteMods.preload = preload;

        if (preload) {
            new Wait(500);
            if (!delModsTxt.exists() && out.exists()) {
                new UnZipper(out, new File("./"), false, "delmods.txt");
            }
        }

        modsDeleted = true;
        int tryCountMods = 1;

        Delete();

        while (true) {
            if (tryCountMods == 10) {
                LOGGER.error("AUTOMODPACK -- ERROR - DELETING MODS FAILED!");
                LOGGER.error("AUTOMODPACK -- ERROR - DELETING MODS FAILED!");
                LOGGER.error("AUTOMODPACK -- ERROR - DELETING MODS FAILED!");
                break;
            }
            if (!modsDeleted) {
                tryCountMods++;
                LOGGER.warn("Trying to delete mods again... " + tryCountMods);
                modsDeleted = true;
                Delete();
            }
            if (modsDeleted) {
                break;
            }
        }

        // Delete the file
        try {
            FileDeleteStrategy.FORCE.delete(delModsTxt);
        } catch (IOException ignored) { }


        LOGGER.info("Finished deleting mods!");

        if (!preload) {
            AutoModpackMain.ModpackUpdated = ModpackUpdated;
        }
    }

    private void Delete() {

        if (!delModsTxt.exists() || delModsTxt.length() == 0) {
            if (preload) {
                return;
            }
            AutoModpackMain.ModpackUpdated = ModpackUpdated;
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
                            new Zipper(new File("./AutoModpack/TrashMod/"), modFile);
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
                        modsDeleted = false;
                    }
                }
            }

            // Close the file
            inFile.close();
        } catch (IOException ignored) { }
    }
}