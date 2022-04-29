package pl.skidam.automodpack.Modpack;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.Finished;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;

public class DeleteMods {

    boolean AllDone = false;
    boolean DelModsTxtDone = false;
    public DeleteMods() {

        Thread.currentThread().setName("AutoModpack - DeleteOldMods");
        Thread.currentThread().setPriority(10);

        DelModsTxt();

        while (DelModsTxtDone != false) {
            DelMods();
        }

        while (AllDone != false) {
            new Finished();
        }
    }

    public void DelModsTxt() {

        // Add old mods by txt file to delmods folder/list
        File delModsTxt = new File("./delmods.txt");
        if (delModsTxt.exists()) {
            try {
                FileReader fr = new FileReader(delModsTxt);
                Scanner inFile = new Scanner(fr);

                if (delModsTxt.length() != 0) {

                    while (inFile.hasNextLine()) {
                        // Read the first line from the file.
                        String line = inFile.nextLine();
                        // Create fake mod file
                        File DelMod = new File("./delmods/" + line);
                        if (!DelMod.exists()) {
                            DelMod.createNewFile();
                        }
                    }
                }
                // Close the file.
                inFile.close();

            } catch (IOException e) {
                System.out.println("Error while reading delmods.txt");
            }

            try {
                FileDeleteStrategy.FORCE.delete(delModsTxt);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        DelModsTxtDone = true;
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
                        Files.copy(delMods.toPath(), new File("./mods/" + name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    try {
                        FileDeleteStrategy.FORCE.delete(oldMod);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }


                    System.out.println("AutoModpack -- Successfully deleted: " + name);
                }
            }
        }

        if (delMods.exists()) {
            try {
                FileUtils.forceDelete(delMods);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        AllDone = true;
    }
}
