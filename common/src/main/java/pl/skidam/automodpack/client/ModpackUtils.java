package pl.skidam.automodpack.client;

import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.ModpackContentTools;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static pl.skidam.automodpack.StaticVariables.*;
import static pl.skidam.automodpack.client.ModpackUpdater.getServerModpackContent;

public class ModpackUtils {

    public enum UpdateType { FULL, DELETE, NONE }

    public static void printoutMissingMods(Config.ModpackContentFields modpackContent) {
        if (modpackContent == null) {
            LOGGER.error("Modpack content is null");
            return;
        }

        // We don't mind here if some mod isn't loaded, loader is broken lol
        // This method returns false if some file is missing

        Collection modList = Platform.getModList();
        if (modList.size() == 0) {
            LOGGER.error("modList is empty");
            return;
        }

        int i = 0;
        Map<String, Integer> loadedMods = new HashMap<>();
        for (Object mod : modList) {
            i++;
            String modId = mod.toString().split(" ")[0]; // mod is  "modid (version)" so we remove everything after space to get modid (modid can't have space in it)
            loadedMods.put(modId, i);
        }

        for (Config.ModpackContentFields.ModpackContentItems mod : modpackContent.list) {
            if (mod.modId == null) continue;

            // check if file is in mods folder
            File modpackDir = selectedModpackDir;
            File modFile = new File(modpackDir + mod.file);
            if (modFile.exists()) {
                String env = Platform.getModEnvironmentFromNotLoadedJar(modFile);
                if (env.equals("SERVER")) continue;
            }

            if (!loadedMods.containsKey(mod.modId)) {
                LOGGER.warn(mod.file + " is missing!");
            }
        }
    }

    // If update to modpack found, returns true else false
    public static UpdateType isUpdate(String link, File modpackDir) {
        if (link == null || modpackDir.toString() == null) {
            LOGGER.error("Modpack link or modpack directory is null");
            return UpdateType.NONE;
        }

        Config.ModpackContentFields serverModpackContent = getServerModpackContent(link);

        if (serverModpackContent == null || serverModpackContent.list == null) {
            LOGGER.error("Server modpack content list is null");
            return UpdateType.NONE;
        }

        try {
            // delete files that are not in server modpack content
            boolean deletedSomething = deleteUnwantedFiles(modpackDir, serverModpackContent, ModpackContentTools.getModpackContentFile(modpackDir), modpackDir);
            ModpackUtils.deletedSomething = false; // revert it back
            boolean isUpdate = false;

            // check if every file in server modpack content exists and has same checksum
            for (Config.ModpackContentFields.ModpackContentItems modpackFile : serverModpackContent.list) {
                File file = new File(modpackDir + File.separator + modpackFile.file);

                if (!file.exists()) {
                    file = new File("./" + modpackFile.file);
                }

                if (!file.exists()) {
                    LOGGER.error(modpackFile.file + " -- update 3");
                    isUpdate = true;
                    break;
                }

                String serverChecksum = modpackFile.hash;
                String localChecksum = CustomFileUtils.getHash(file, "SHA-256");
                if (!serverChecksum.equals(localChecksum)) {
                    if (modpackFile.type.equals("mod")) { // that's a bit broken, it shouldn't be like that, but it needs to be because some files returns different checksums somehow....
                        LOGGER.error(modpackFile.file + " -- update 1");
                        isUpdate = true;
                        break;
                    } else if (Long.parseLong(modpackFile.size) != file.length() && !modpackFile.isEditable) {
                        LOGGER.error(modpackFile.file + " -- update 2 " + modpackFile.size + " <--> " + file.length());
                        isUpdate = true;
                        break;
                    }
                }
            }

            if (isUpdate) {
                LOGGER.warn("Modpack update found!");
                return UpdateType.FULL;
            } else if (deletedSomething && preload) {
                LOGGER.warn("Modpack is up to date, but some files were deleted. We need to restart the game to apply changes.");
                new ReLauncher.Restart(modpackDir);
                return UpdateType.DELETE;
            } else if (deletedSomething) { // and not preload
                LOGGER.warn("Modpack is up to date, but some files were deleted. We need to restart the game to apply changes.");
                return UpdateType.DELETE;
            }

            LOGGER.info("Modpack is up to date!");
            return UpdateType.NONE;
        } catch (Exception e) {
            LOGGER.error("Error while checking modpack update", e);
            e.printStackTrace();
            return UpdateType.NONE;
        }
    }

    private static boolean deletedSomething = false;

    private static boolean deleteUnwantedFiles(File directory, Config.ModpackContentFields serverModpackContent, File contentFile, File modpackFile) throws Exception {
        File[] files = directory.listFiles();
        if (files == null) return false;

        List<Config.ModpackContentFields.ModpackContentItems> serverModpackContentCopy = new ArrayList<>(serverModpackContent.list);
        for (File file : files) {

            if (file.equals(contentFile)) continue;

            if (file.isDirectory()) {
                deleteUnwantedFiles(file, serverModpackContent, contentFile, modpackFile);
                continue;
            }

            boolean found = false;
            for (Config.ModpackContentFields.ModpackContentItems modpackFileFromContent : serverModpackContentCopy) {
                String modpackPath = file.getAbsolutePath().replace(modpackFile.getAbsolutePath(), "").replace("\\", "/");

                if (!modpackPath.equals(modpackFileFromContent.file)) continue;

                found = true;
                serverModpackContentCopy.remove(modpackFileFromContent);
                break;
            }

            if (found) continue;

            LOGGER.warn("Didn't found {} in modpack, deleting it", file.getName());
            deletedSomething = true;
            CustomFileUtils.forceDelete(file, true);
            ModpackUpdater.changelogList.put(file.getName(), false);

            // Deleting file from running directory if it's the same file as in modpack
            File runningFile = new File("." + modpackFile);
            if (!runningFile.exists() || !runningFile.isFile()) continue;

            String runningChecksum = CustomFileUtils.getHash(runningFile, "SHA-256");
            String modpackChecksum = CustomFileUtils.getHash(file, "SHA-256");

            if (runningChecksum == null || modpackChecksum == null) continue;
            if (!runningChecksum.equals(modpackChecksum)) continue;

            LOGGER.warn("Found same file in running directory, deleting it");
            CustomFileUtils.forceDelete(runningFile, true);
        }

        return deletedSomething;
    }

    public static void copyModpackFilesFromModpackDirToRunDir(File modpackDir, Config.ModpackContentFields serverModpackContent) throws IOException {
        List<Config.ModpackContentFields.ModpackContentItems> contents = serverModpackContent.list;

        for (Config.ModpackContentFields.ModpackContentItems contentItem : contents) {
            String fileName = contentItem.file;
            File sourceFile = new File(modpackDir + File.separator + fileName);

            if (sourceFile.exists()) {
                File destinationFile = new File("." + fileName);

                if (destinationFile.exists()) {
                    CustomFileUtils.forceDelete(destinationFile, false);
                }

                CustomFileUtils.copyFile(sourceFile, destinationFile);
                LOGGER.info("Copied " + fileName + " to running directory");
            }
        }
    }


    public static void copyModpackFilesFromRunDirToModpackDir(File modpackDir, Config.ModpackContentFields serverModpackContent) throws Exception {
        List<Config.ModpackContentFields.ModpackContentItems> contents = serverModpackContent.list;

        for (Config.ModpackContentFields.ModpackContentItems contentItem : contents) {
            File sourceFile = new File("./" + contentItem.file);

            if (sourceFile.exists()) {

                // check hash
                String serverChecksum = contentItem.hash;
                String localChecksum = CustomFileUtils.getHash(sourceFile, "SHA-256");

                if (!serverChecksum.equals(localChecksum) && !contentItem.isEditable) {
                    continue;
                }

                File destinationFile = new File(modpackDir + File.separator + contentItem.file);

                if (destinationFile.exists()) {
                    CustomFileUtils.forceDelete(destinationFile, false);
                }

                CustomFileUtils.copyFile(sourceFile, destinationFile);
                LOGGER.info("Copied " + sourceFile.getName() + " to modpack directory");
            }
        }
    }
}
