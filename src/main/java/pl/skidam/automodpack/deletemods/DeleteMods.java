package pl.skidam.automodpack.deletemods;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.utils.Wait;

import java.io.*;
import java.util.Objects;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

        if (!delModsTxt.exists()) {
            return;
        };
        if (delModsTxt.length() == 0) {
            return;
        };

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

                    if (modName.exists()) { // if Delmods still exists
                        // MAGIC TACTIC
                        TrashThisMod(modName);
                    }

                    if (modName.exists()) { // if Delmods still exists
                        // Try to delete it again
                        FileDeleteStrategy.FORCE.delete(modName);
                    }


                    LOGGER.info("Successfully deleted: " + modNameLine);
                }
            }

            // Close the file
            inFile.close();

            // Delete the file
            try {
                FileDeleteStrategy.FORCE.delete(delModsTxt);
            } catch (IOException e) { // ignore it
            }

        } catch (IOException e) {
            LOGGER.error("Error while reading delmods.txt");
        }

        LOGGER.info("Finished deleting mods!");

        if (!preload) {
            AutoModpackMain.ModpackUpdated = this.ModpackUpdated;
        }

    }

    private void TrashThisMod(File modName) {

        try {
            FileOutputStream fos = new FileOutputStream(modName);
            ZipOutputStream zos = new ZipOutputStream(fos);
            zos.flush();

            // TODO CLEAN IT PLEASE (it's trash XD)              ... but it works :)

            for (File file : Objects.requireNonNull(new File("./AutoModpack/TrashMod/").listFiles())) {
                if (file.isFile()) {
                    zos.putNextEntry(new ZipEntry(file.getName()));
                    zos.write(FileUtils.readFileToByteArray(file));
                    zos.closeEntry();
                }
                if (file.isDirectory()) {
                    zos.putNextEntry(new ZipEntry(file.getName() + "/"));
                    zos.closeEntry();
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

            LOGGER.warn("Successfully converted to TrashMod: " + modName);

            } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
