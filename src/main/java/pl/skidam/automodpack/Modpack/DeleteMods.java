package pl.skidam.automodpack.Modpack;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;

public class DeleteMods implements Runnable{

    public DeleteMods() {
    }

    @Override
    public void run() {


        Thread.currentThread().setName("AutoModpack - DeleteOldMods");
        Thread.currentThread().setPriority(10);

        // Add old mods by txt file to delmods folder/list
        // TODO FIX IT
        File oldModsTxt = new File("./delmods.txt");
        if (oldModsTxt.exists()) {
            try {
                FileReader fr = new FileReader(oldModsTxt);
                Scanner inFile = new Scanner(fr);

                String line;

                // Read the first line from the file.
                line = inFile.nextLine();

                while (inFile.hasNextLine()) {
                    File DelMod = new File("./delmods/" + line);
                    if (!DelMod.exists()) {
                        DelMod.createNewFile();
                    }
                    line = inFile.nextLine();
                }

                // Close the file.
                inFile.close();

            } catch (IOException e) {
                System.out.println("Error while reading delmods.txt");
            }

            try {
                FileUtils.forceDelete(oldModsTxt);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }


        // Delete old mods by deleting the folder
        File oldMods = new File("./delmods/");
        String[] oldModsList = oldMods.list();
        if (oldMods.exists()) {
            assert oldModsList != null;
            for (String name : oldModsList) {
                File oldMod = new File("./mods/" + name);
                if (name.endsWith(".jar") && !name.equals("AutoModpack.jar") && oldMod.exists()) {
                    System.out.println("AutoModpack -- Deleting: " + name);

                    try {
                        Files.copy(oldMods.toPath(), new File("./mods/" + name).toPath(), StandardCopyOption.REPLACE_EXISTING);
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

        if (oldMods.exists()) {
            try {
                FileUtils.forceDelete(oldMods);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
