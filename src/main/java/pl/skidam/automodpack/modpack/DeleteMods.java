package pl.skidam.automodpack.modpack;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.Finished;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;

public class DeleteMods {

    public DeleteMods() {

        Thread.currentThread().setName("AutoModpack - DeleteOldMods");
        Thread.currentThread().setPriority(10);

        DelModsTxt();

        //TODO make it better

    }

    public void DelModsTxt() {

        // Add old mods by txt file to delmods folder/list
        File delModsTxt = new File("./delmods.txt");
        if (delModsTxt.exists()) {
            try {
                if (delModsTxt.length() != 0) {

                    File delModsFile = new File("./delmods/");
                    if (!delModsFile.exists()) {
                        delModsFile.mkdir();
                    }

                    FileReader fr = new FileReader(delModsTxt);
                    Scanner inFile = new Scanner(fr);

                    while (inFile.hasNextLine()) {
                        // Read the first line from the file.
                        String line = inFile.nextLine();
                        // Create fake mod file
                        File DelMod = new File("./delmods/" + line);
                        if (!DelMod.exists()) {
                            DelMod.createNewFile();
}
                    }
                    // Close the file
                    inFile.close();

                    // Delete the file
                    try {
                        FileDeleteStrategy.FORCE.delete(delModsTxt);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

            } catch (IOException e) {
                System.out.println("Error while reading delmods.txt");
            }

            DelMods();
        }
    }

    public void DelMods() {

        // Delete old mods by deleting the folder
        File delMods = new File("./delmods/");
        String[] oldModsList = delMods.list();
        if (delMods.exists()) {
            assert oldModsList != null;
            for (String name : oldModsList) {
                File oldMod = new File("./mods/" + name);
                if (name.endsWith(".jar") && !name.equals("AutoModpack.jar") && oldMod.exists()) {
                    System.out.println("AutoModpack -- Deleting: " + name);
                    try {
                        FileReader fr = new FileReader(oldMod);
                        Scanner inFile = new Scanner(fr);

                        // Unload mod from modloader to delete it

                        inFile.close();

                        FileDeleteStrategy.FORCE.delete(oldMod);
                    } catch (IOException e) { // ignore it
                    }

                    System.out.println("AutoModpack -- Successfully deleted: " + name);
                }
            }
        }

        new Finished();
    }
}
