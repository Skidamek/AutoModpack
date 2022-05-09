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
import java.io.IOException;
import java.util.Scanner;

public class DeleteMods implements Runnable {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");

    File delModsTxt = new File("./delmods.txt");
    boolean preload;

    public DeleteMods(boolean preload) {

        Thread.currentThread().setName("AutoModpack - DeleteOldMods");
        Thread.currentThread().setPriority(10);

        if (preload) {
            this.preload = true;
            wait(500);
            if (!delModsTxt.exists()) {
                try {
                    new ZipFile("./AutoModpack/modpack.zip").extractFile("delmods.txt", "./");
                    new ZipFile("./AutoModpack/modpack.zip").extractFile("delmods", "./");
                } catch (ZipException e) { // ignore it
                }
            }
            //TODO make it better
        }
        if (!preload) {
            this.preload = false;
        }

        DelModsTxt();
    }

    public void DelModsTxt() {

        // Add old mods by txt file to delmods folder/list
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
                LOGGER.error("Error while reading delmods.txt");
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
                        FileDeleteStrategy.FORCE.delete(oldMod);
                    } catch (IOException e) { // ignore it
                    }

                    if (oldMod.exists()) {

                        try {
                            Scanner inFile = new Scanner(new FileReader(oldMod));
                            inFile.close();

                            new ZipFile(oldMod).extractAll("./AutoModpack/delfiles/" + name);

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

                            // Delete mod file
                            FileDeleteStrategy.FORCE.delete(oldMod);

                        } catch (IOException e) { // ignore it
                        }
                    }

                    LOGGER.info("AutoModpack -- Successfully deleted: " + name);

                    // delete delfiles folder
                    File delfiles = new File("./AutoModpack/delfiles/");
                    if (delfiles.exists()) {
                        try {
                            FileUtils.deleteDirectory(new File(String.valueOf(delfiles)));
                        } catch (IOException e) {
                            System.out.println(e.getMessage());
                        }
                    }
                    LOGGER.info("AutoModpack -- Finished deleting old mods");
                }
            }
            try {
                FileDeleteStrategy.FORCE.delete(new File("./delmods/"));
            } catch (IOException e) { // ignore it
            }
        }

        if (!preload) {
            new Finished();
        }

    }

    @Override
    public void run() {

    }

    private static void wait(int ms) {
        try
        {
            Thread.sleep(ms);
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }
}
