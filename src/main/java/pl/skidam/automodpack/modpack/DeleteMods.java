package pl.skidam.automodpack.modpack;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.Finished;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Date;
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
                    // Close the file.
                    inFile.close();
                }

            } catch (IOException e) {
                System.out.println("Error while reading delmods.txt");
            }

            try {
                FileUtils.writeStringToFile(delModsTxt, "");
                FileUtils.deleteQuietly(delModsTxt);
            } catch (IOException e) {
                throw new RuntimeException(e);
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

                    //Create some files here
                    File sourceFile = new File(delMods.getPath() + "/" + name);
                    File fileToCopy = new File("./mods/" + name);

                    try {
                        FileUtils.writeStringToFile(sourceFile, "Deleted by AutoModpack on " + new Date() + " ~Skidam");
                        FileUtils.copyFile(sourceFile, fileToCopy);
                        FileUtils.deleteQuietly(sourceFile);
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

        new Finished();
    }
}
