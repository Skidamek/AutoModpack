package pl.skidam.automodpack.delmods;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.skidam.automodpack.Finished;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;

public class DeleteMods implements Runnable {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");

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
                    } catch (IOException e) { // ignore it
                    }
                }

            } catch (IOException e) {
                System.out.println("Error while reading delmods.txt");
                DelMods();
            }
        }
        DelMods();
    }

    public void DelMods() {

        // Delete old mods by deleting the folder
        File delMods = new File("./delmods/");
        String[] oldModsList = delMods.list();
        if (delMods.exists()) {
            assert oldModsList != null;
            try {
                new ZipFile("./AutoModpack/TrashMod.jar").extractAll("./AutoModpack/TrashMod/");
            } catch (ZipException e) {
                throw new RuntimeException(e);
            }
            for (String name : oldModsList) {
                File oldMod = new File("./mods/" + name);
                if (name.endsWith(".jar") && !name.equals("AutoModpack.jar") && oldMod.exists()) {
                    LOGGER.info("AutoModpack -- Deleting: " + name);
                    try {
                        Scanner inFile = new Scanner(new FileReader(oldMod));
                        inFile.close();

                        new ZipFile(oldMod).extractAll("./" + name);

                        for (File file : new File("./AutoModpack/delfiles/" + name).listFiles()) {
                            new ZipFile(oldMod).removeFile(file.getAbsolutePath());
                        }

                        for (File TMfile : new File("./AutoModpack/TrashMod/").listFiles()) {
                            if (TMfile.isFile()) {
                                new ZipFile(oldMod).addFile(TMfile.getAbsolutePath());
                            } else {
                                new ZipFile(oldMod).addFolder(new File(TMfile.getAbsolutePath()));
                            }
                        }

                    } catch (IOException e) { // ignore it
                        System.out.println(e.getMessage());
                    }

                    LOGGER.info("AutoModpack -- Successfully deleted: " + name);
                }
            }
        }

//        new Finished();
    }

    @Override
    public void run() {
        new DeleteMods();
    }
}
