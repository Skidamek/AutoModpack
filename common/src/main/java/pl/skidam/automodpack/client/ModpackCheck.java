package pl.skidam.automodpack.client;

import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.ModpackContentTools;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static pl.skidam.automodpack.AutoModpack.LOGGER;
import static pl.skidam.automodpack.client.ModpackUpdater.getServerModpackContent;

public class ModpackCheck {

    public enum UpdateType { FULL, DELETE, NONE }

    public static boolean isLoaded(Config.ModpackContentFields modpackContent) {
        Collection modList = Platform.getModList();
        if (modList.size() == 0) {
            LOGGER.error("modList is empty");
            return false;
        }

        int i = 0;
        Map<String, Integer> localMods = new HashMap<>();
        for (Object mod : modList) {
            i++;
            String modId = mod.toString().split(" ")[0]; // mod is  "modid (version)" so we remove everything after space to get modid (modid can't have space in it)
            localMods.put(modId, i);
        }

        for (Config.ModpackContentFields.ModpackContentItems mod : modpackContent.list) {
            if (mod.modId == null) continue;

            // check if file is in mods folder
            File modpackDir = AutoModpack.selectedModpackDir;
            File modFile = new File(modpackDir + mod.file);
            if (modFile.exists()) {
                String env = Platform.getModEnvironmentFromNotLoadedJar(modFile);
                if (env.equals("SERVER")) continue;
            }

            if (!localMods.containsKey(mod.modId)) {
                LOGGER.warn("Modpack is not loaded");
                return false;
            }
        }

        return true;
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
            ModpackCheck.deletedSomething = false; // revert it back
            boolean isUpdate = false;

            // check if every file in server modpack content exists and has same checksum
            for (Config.ModpackContentFields.ModpackContentItems modpackFile : serverModpackContent.list) {
                File file = new File(modpackDir + File.separator + modpackFile.file);
                if (!file.exists()) {
                    isUpdate = true;
                    break;
                }

                String serverChecksum = modpackFile.hash;
                String localChecksum = CustomFileUtils.getSHA512(file);
                if (!serverChecksum.equals(localChecksum)) {
                    isUpdate = true;
                    break;
                }
            }


            if (isUpdate) {
                LOGGER.warn("Modpack update found!");
                return UpdateType.FULL;
            } else if (deletedSomething && AutoModpack.preload) {
                LOGGER.warn("Modpack is up to date, but some files were deleted. We need to restart the game to apply changes."); // TODO it might be problem with GameOptionsMixin
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

    private static boolean deleteUnwantedFiles(File directory, Config.ModpackContentFields serverModpackContent, File contentFile, File modpackDir) throws Exception {
        File[] files = directory.listFiles();
        if (files != null) {
            List<Config.ModpackContentFields.ModpackContentItems> serverModpackContentCopy = serverModpackContent.list;
            for (File file : files) {
                if (file.equals(contentFile)) continue;
                if (file.isDirectory()) {
                    deleteUnwantedFiles(file, serverModpackContent, contentFile, modpackDir);
                } else {
                    boolean found = false;
                    for (Config.ModpackContentFields.ModpackContentItems modpackFile : serverModpackContentCopy) {
                        String modpackPath = file.getAbsolutePath().replace(modpackDir.getAbsolutePath(), "").replace("\\", "/");
//                        LOGGER.error(modpackPath + " <-> " + modpackFile.file);
                        if (modpackPath.equals(modpackFile.file)) {
                            found = true;
                            serverModpackContentCopy.remove(modpackFile);
                            break;
                        }
                    }
                    if (!found) {
                        LOGGER.warn("Didn't found {} in modpack, deleting it", file.getName());
                        deletedSomething = true;
                        CustomFileUtils.forceDelete(file);
                        ModpackUpdater.changelogList.put(file.getName(), false);

                        // Deleting file from running directory if it's the same file as in modpack
                        File runningFile = new File("." + modpackDir);
                        if (runningFile.exists() && runningFile.isFile()) {
                            String runningChecksum = CustomFileUtils.getSHA512(runningFile);
                            String modpackChecksum = CustomFileUtils.getSHA512(file);

                            if (runningChecksum.equals(modpackChecksum)) {
                                long runningSize = runningFile.length();
                                long modpackSize = file.length();
                                if (runningSize == modpackSize) {
                                    LOGGER.warn("Found same file in running directory, deleting it");
                                    CustomFileUtils.forceDelete(runningFile);
                                }
                            }
                        }
                    }
                }
            }
        }

        return deletedSomething;
    }
}
