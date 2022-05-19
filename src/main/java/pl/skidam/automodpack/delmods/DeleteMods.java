package pl.skidam.automodpack.delmods;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.AutoModpackClient;

import java.io.*;
import java.util.Objects;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DeleteMods {

    File delModsTxt = new File("./delmods.txt");
    boolean preload;
    String ModpackUpdated;

    public DeleteMods(boolean preload, String ModpackUpdated) {

        Thread.currentThread().setPriority(10);

        this.ModpackUpdated = ModpackUpdated;

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
                AutoModpackClient.LOGGER.error("Error while reading delmods.txt");
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

                // check if the oldMod is in the trashedmods.txt
                boolean TrashedMod = false;
                File trashedMods = new File("./AutoModpack/trashedmods.txt");
                if (trashedMods.exists()) {
                    try {
                        Scanner inFile = new Scanner(new FileReader(trashedMods));
                        while (inFile.hasNextLine()) {
                            // Read the first line from the file.
                            String line = inFile.nextLine();
                            if (line.equals(oldMod.getName())) {
                                // Trashed Mod
                                TrashedMod = true;
                                break;

                            }
                        }
                    } catch (IOException e) { // ignore it
                    }
                }

                if (name.endsWith(".jar") && !name.equals("AutoModpack.jar") && oldMod.exists()) {
                    AutoModpackClient.LOGGER.info("Deleting: " + name);

                    try {
                        Scanner inFile = new Scanner(new FileReader(oldMod));
                        inFile.close();
                        FileDeleteStrategy.FORCE.delete(oldMod);
                    } catch (IOException e) { // ignore it
                    }

                    if (!TrashedMod && oldMod.exists()) {

                        // If mod is worse (don't want to collaboration and self delete), I have to convert it into TrashMod (my mod who does nothing) that is my wierd "delete" system
                        try {
                            AutoModpackClient.LOGGER.warn(name + " is worse, so i have to convert it into TrashMod");
                            FileOutputStream fos = new FileOutputStream(oldMod);
                            ZipOutputStream zos = new ZipOutputStream(fos);
                            zos.flush();

                            // TODO CLEAN IT PLEASE (it's trash XD)              ... but it works :)

                            for (File file : Objects.requireNonNull(new File("./AutoModpack/TrashMod/").listFiles())) {
                                // add folder to this zip
                                if (file.isFile()) {
                                    zos.putNextEntry(new ZipEntry(file.getName()));
                                    zos.write(FileUtils.readFileToByteArray(file));
                                    zos.closeEntry();
                                }
                                // force add directory to this zip
                                if (file.isDirectory()) {
                                    zos.putNextEntry(new ZipEntry(file.getName() + "/"));
                                    zos.closeEntry();
                                    // if in directory add all files in it
                                    for (File file2 : Objects.requireNonNull(file.listFiles())) {
                                        if (file2.isFile()) {
                                            zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName()));
                                            zos.write(FileUtils.readFileToByteArray(file2));
                                            zos.closeEntry();
                                        }
                                        if (file2.isDirectory()) {
                                            zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/"));
                                            zos.closeEntry();

                                            for (File file3 : Objects.requireNonNull(file2.listFiles())) {
                                                if (file3.isFile()) {
                                                    zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName()));
                                                    zos.write(FileUtils.readFileToByteArray(file3));
                                                    zos.closeEntry();
                                                }
                                                if (file3.isDirectory()) {
                                                    zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/"));
                                                    zos.closeEntry();

                                                    for (File file4 : Objects.requireNonNull(file3.listFiles())) {
                                                        if (file4.isFile()) {
                                                            zos.putNextEntry(new ZipEntry(file.getName() + "/" + file2.getName() + "/" + file3.getName() + "/" + file4.getName()));
                                                            zos.write(FileUtils.readFileToByteArray(file4));
                                                            zos.closeEntry();
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }


                            zos.closeEntry();
                            zos.close();

                            // add mod to the trashed mod list
                            File trashedMod = new File("./AutoModpack/trashedmods.txt");
                            if (!trashedMod.exists()) {
                                trashedMod.createNewFile();
                            }
                            FileWriter fw = new FileWriter(trashedMod, true);
                            fw.write(name + "\n");
                            fw.close();

                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        try {
                            Scanner inFile = new Scanner(new FileReader(oldMod));
                            inFile.close();
                            FileDeleteStrategy.FORCE.delete(oldMod);
                        } catch (IOException e) { // ignore it

                        }
                    }

                    if (!TrashedMod) {
                        AutoModpackClient.LOGGER.info("Successfully deleted: " + name);
                    } else {
                        AutoModpackClient.LOGGER.info(name + " is already Trashed, skiping...");
                    }

                    // delete delfiles folder
                    File delfiles = new File("./AutoModpack/delfiles/");
                    if (delfiles.exists()) {
                        try {
                            FileUtils.deleteDirectory(new File(String.valueOf(delfiles)));
                        } catch (IOException e) {
                            System.out.println(e.getMessage());
                        }
                    }
                }
            }
            try {
                FileDeleteStrategy.FORCE.delete(new File("./delmods/"));
            } catch (IOException e) { // ignore it
            }
            AutoModpackClient.LOGGER.info("Finished deleting mods!");
        }

        if (!preload) {
            AutoModpackClient.ModpackUpdated = ModpackUpdated;
        }

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
