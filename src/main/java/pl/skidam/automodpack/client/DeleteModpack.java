package pl.skidam.automodpack.client;

import org.apache.commons.io.FileDeleteStrategy;

import pl.skidam.automodpack.utils.UnZipper;
import pl.skidam.automodpack.utils.Zipper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;
import static pl.skidam.automodpack.AutoModpackMain.out;

public class DeleteModpack {

    private static boolean modsDeleted;
    private static boolean configsDeleted;
    private static int tryCountMods;
    private static int tryCountConfigs;

    public DeleteModpack() {

        LOGGER.warn("Deleting modpack...");
        // unzip modpack.zip
        // get absolute path of current dir

//        System.out.println("Working directory: " + System.getProperty("user.dir"));
//
//        System.out.println("Generating hash...");
//
//        System.out.println("Hash SHA-512: " + GenerateHash.SHA512(new File("./AutoModpack/modpack.zip").length() + ""));
//
//        System.out.println("Hash MD5: " + GenerateHash.MD5(new File("./AutoModpack/modpack.zip").length() + ""));

        new UnZipper(out, new File("./AutoModpack/modpack/"), true, "none");

//        deleteEverything();
//        deleteEverything();

        makeIt();

        Runtime.getRuntime().addShutdownHook(new Thread(DeleteModpack::makeIt));
    }

    private static void makeIt() {

        modsDeleted = true;
        configsDeleted = true;

        tryCountMods = 1;
        tryCountConfigs = 1;

        deleteMods();
        deleteConfigs();

        while (true) {
            if (tryCountMods == 10) {
                LOGGER.error("AUTOMODPACK -- ERROR - DELETING MODPACK (MODS) FAILED!");
                LOGGER.error("AUTOMODPACK -- ERROR - DELETING MODPACK (MODS) FAILED!");
                LOGGER.error("AUTOMODPACK -- ERROR - DELETING MODPACK (MODS) FAILED!");
                break;
            }
            if (!modsDeleted) {
                tryCountMods++;
                LOGGER.warn("Trying to delete mods again... " + tryCountMods);
                modsDeleted = true;
                deleteMods();
            }
            if (modsDeleted) {
                break;
            }
        }

        while (true) {
            if (tryCountConfigs == 10) {
                LOGGER.error("AUTOMODPACK -- ERROR - DELETING MODPACK (CONFIGS) FAILED!");
                LOGGER.error("AUTOMODPACK -- ERROR - DELETING MODPACK (CONFIGS) FAILED!");
                LOGGER.error("AUTOMODPACK -- ERROR - DELETING MODPACK (CONFIGS) FAILED!");
                break;
            }
            if (!configsDeleted) {
                tryCountConfigs++;
                LOGGER.warn("Trying to delete configs again... " + tryCountConfigs);
                configsDeleted = true;
                deleteConfigs();
            }
            if (configsDeleted) {
                break;
            }
        }

        // Delete unzipped modpack dir, modpack.zip and modpack-link.txt
        try {
            FileDeleteStrategy.FORCE.delete(new File("./AutoModpack/modpack/"));
            FileDeleteStrategy.FORCE.delete(out);
            FileDeleteStrategy.FORCE.delete(new File("./AutoModpack/modpack-link.txt"));
        } catch (Exception e) { // ignore it
        }

        LOGGER.info("Finished deleting modpack!");
        LOGGER.info("Restart your game!");
    }

    // TODO: delete everything method
//    Private static void deleteEverything() {
//        // Make array of all files in modpack dir
//        File[] files = new File("./AutoModpack/modpack/").listFiles();
//
//        if (files.length == 0) {
//            LOGGER.info("Nothing to delete...");
//            return;
//        }
//
//        LOGGER.info("Deleting everything...");
//        for (File file : files) {
//            LOGGER.info(file.getName() + ":");
//            File[] filesInDir = file.listFiles();
//            if (filesInDir == null) {
//                LOGGER.info("null!");
//                return;
//            } else {
//                for (File fileInDir : filesInDir) {
//                    LOGGER.info("\t" + fileInDir.getName());
//                    fileInDir.delete();
//                }
//                file.delete();
//            }
//        }
//    }

//    public static void main(String[] args) {
//        // user path
//        System.out.println("Working directory: " + System.getProperty("user.dir"));
//
//        try {
//            new Ziper(new File("./run/AutoModpack/TrashMod/"), new File("./run/mods/AutoModpack-1.19.x.jar"));
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }

    private static void deleteMods() {
        // MODS
        // make array of file names "./AutoModpack/modpack/mods/" folder
        File[] modpackModsFiles = new File("./AutoModpack/modpack/mods/").listFiles();

        // Loop to delete all names in ./mods/ folder of names in files in "./AutoModpack/modpack/mods/"
        for (File modpackModName : modpackModsFiles) {
            String modName = modpackModName.getName();
            File modFile = new File("./mods/" + modName);

            if (modFile.exists()) {

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
    }

    private static void deleteConfigs() {

        // CONFIGS
        // Make array of file names "./AutoModpack/modpack/config/" folder
        File[] modpackConfigFiles = new File("./AutoModpack/modpack/config/").listFiles();

        // Loop to delete all names in ./config/ folder of names in files in "./AutoModpack/modpack/config/"
        for (File modpackConfigName : modpackConfigFiles) {

            String configName = modpackConfigName.getName();
            File configFile = new File("./config/" + configName);

            if (configFile.exists()) {
                try {
                    if (configFile.exists()) {
                        LOGGER.info("Deleting: " + configName);
                        FileDeleteStrategy.FORCE.delete(configFile);
                    }
                    LOGGER.info("Successfully deleted: " + configName);
                } catch (IOException e) { // ignore
                    configsDeleted = false;
                    e.printStackTrace();
                }
            }
        }
    }
}