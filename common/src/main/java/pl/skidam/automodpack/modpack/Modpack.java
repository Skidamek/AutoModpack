package pl.skidam.automodpack.modpack;

import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.JarUtilities;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static pl.skidam.automodpack.StaticVariables.*;
public class Modpack {
    public static Path hostModpackDir = Path.of(automodpackDir + File.separator + "host-modpack");
    static Path hostModpackMods = Path.of(hostModpackDir + File.separator + "mods");
    public static File hostModpackContentFile = new File(hostModpackDir + File.separator + "modpack-content.json");

    public static void generate() {

        long start = System.currentTimeMillis();

        try {
            if (!hostModpackDir.toFile().exists()) Files.createDirectories(hostModpackDir);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Content.create(hostModpackDir, hostModpackContentFile);
        if (!hostModpackContentFile.exists()) return;
        LOGGER.info("Modpack generated! took " + (System.currentTimeMillis() - start) + "ms");
    }

    private static void autoExcludeServerMods(List<Config.ModpackContentFields.ModpackContentItems> list) {

        if (Platform.Forge) return;

        List<String> removeSimilar = new ArrayList<>();

        Collection modList = Platform.getModList();

        for (Object mod : modList) {
            String modId = mod.toString().split(" ")[0]; // mod is  "modid (version)" so we remove everything after space to get modid (modid can't have space in it)
            String modEnv = Platform.getModEnvironment(modId).toUpperCase();
            LOGGER.warn("Mod {} has environment {}", modId, modEnv);
            if (modEnv == null) continue;
            if (modEnv.equals("SERVER")) {
                list.removeIf(modpackContentItems -> {
                    if (modpackContentItems.modId == null) return false;
                    if (modpackContentItems.modId.equals(modId)) {
                        LOGGER.info("Mod {} has been auto excluded from modpack because it is server side mod", modId);
                        removeSimilar.add(modId);
                        return true;
                    }
                    return false;
                });
            }
        }

        for (String modId : removeSimilar) {
            list.removeIf(modpackContentItems -> {
                if (modpackContentItems.type.equals("mod")) return false;
                File contentFile = new File(hostModpackMods + File.separator + modpackContentItems.file);
                String contentFileName = contentFile.getName();
                if (contentFileName.contains(modId)) {
                    LOGGER.info("File {} has been auto excluded from modpack because mod of this file is already excluded", contentFileName);
                    return true;
                }
                return false;
            });
        }
    }

    private static void removeAutoModpackFilesFromContent(List<Config.ModpackContentFields.ModpackContentItems> list) {
        list.removeIf(modpackContentItems -> modpackContentItems.file.toLowerCase().contains("automodpack"));
    }

    public static class Content {
        public static Config.ModpackContentFields modpackContent;

        public static void create(Path modpackDir, File modpackContentFile) {
            try {
                List<Config.ModpackContentFields.ModpackContentItems> list = new ArrayList<>();

                if (serverConfig.syncedFiles.size() > 0) {
                    for (String file : serverConfig.syncedFiles) {
                        LOGGER.info("Syncing {}... ", file);
                        File fileToSync = new File("." + file);
                        addAllContent(fileToSync, list);
                    }
                }

                addAllContent(modpackDir.toFile(), list);

                if (list.size() == 0) {
                    LOGGER.warn("Modpack is empty! Nothing to generate!");
                    return;
                }

                removeAutoModpackFilesFromContent(list);
                if (serverConfig.autoExcludeServerSideMods) {
                    autoExcludeServerMods(list);
                }

                modpackContent = new Config.ModpackContentFields(null, list);
                modpackContent.timeStamp = modpackDir.toFile().lastModified();
                modpackContent.modpackName = serverConfig.modpackName;
                modpackContent.loader = Platform.getPlatformType().toString().toLowerCase();

                ConfigTools.saveConfig(modpackContentFile, modpackContent);

                HttpServer.filesList.clear();
                for (Config.ModpackContentFields.ModpackContentItems item : list) {
                    HttpServer.filesList.add(item.file);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        private static void addAllContent(File modpackDir, List<Config.ModpackContentFields.ModpackContentItems> list) throws Exception {
            if (!modpackDir.exists() || modpackDir.listFiles() == null) return;

            File[] modpackDirFiles = modpackDir.listFiles();
            if (modpackDirFiles == null) return;

            for (File file : modpackDirFiles) {
                if (file.isDirectory()) {
                    if (file.getName().startsWith(".")) continue;
                    addAllContent(file, list);
                } else if (file.isFile()) {
                    if (file.equals(hostModpackContentFile)) continue;
                    String modpackFile = file.toString().replace(hostModpackDir.toString(), "").replace("\\", "/");
                    if (modpackFile.charAt(0) == '.') modpackFile = modpackFile.substring(1);
                    String link = modpackFile;
                    String size = file.length() + "";
                    String type = "other";
                    String modId = null;
                    String version = null;
                    boolean isEditable = false;

                    if (!modpackDir.toString().startsWith("./automodpack/host-modpack/")) {
                        boolean excluded = false;
                        for (String excludeFile : serverConfig.excludeSyncedFiles) {
                            if (matchesExclusionCriteria(modpackFile, excludeFile)) { // wild cards e.g. *.json or supermod-1.19-*.jar
                                excluded = true;
                                break;
                            }
                        }
                        if (excluded) {
                            LOGGER.info("File {} is excluded! Skipping..." + modpackFile);
                            continue;
                        }
                    }


                    if (size.equals("0")) {
                        LOGGER.warn("File {} is empty! Skipping...", modpackFile);
                        continue;
                    }

                    if (!modpackDir.equals(hostModpackDir.toFile())) {
                        if (modpackFile.endsWith(".tmp")) {
                            LOGGER.warn("File {} is temporary! Skipping...", modpackFile);
                            continue;
                        }

                        if (modpackFile.endsWith(".disabled")) {
                            LOGGER.warn("File {} is disabled! Skipping...", modpackFile);
                            continue;
                        }
                    }

                    String hash = CustomFileUtils.getHash(file, "SHA-256");

                    if (file.getName().endsWith(".jar")) {
                        modId = JarUtilities.getModIdFromJar(file, true);
                        type = modId == null ? "other" : "mod";
                        version = JarUtilities.getModVersion(file);
                    }

                    if (type.equals("other")) {
                        if (modpackFile.contains("/config/")) {
                            type = "config";
                        } else if (modpackFile.contains("/shaderpacks/")) {
                            type = "shaderpack";
                        } else if (modpackFile.contains("/resourcepacks/")) {
                            type = "resourcepack";
                        } else if (modpackFile.endsWith("/options.txt")) {
                            type = "mc_options";
                        }
                    }

                    for (String editableFile : serverConfig.allowEditsInFiles) {
                        if (modpackFile.endsWith(editableFile)) {
                            isEditable = true;
                            break;
                        }
                    }

                    // It should overwrite existing file in the list
                    // because first this syncs files from server running dir
                    // And then it gets files from host-modpack dir
                    // So we want to overwrite files from server running dir with files from host-modpack dir
                    // if there are likely same or a bit changed
                    for (Config.ModpackContentFields.ModpackContentItems item : list) {
                        if (item.file.equals(modpackFile)) {
                            list.remove(item);
                            break;
                        }
                    }

                    list.add(new Config.ModpackContentFields.ModpackContentItems(modpackFile, link, size, type, isEditable, modId, version, hash));
                }
            }
        }

        private static boolean matchesExclusionCriteria(String modpackFile, String excludeFile) {
            if (excludeFile.contains("*")) { // wild cards magic
                String[] excludeFileParts = excludeFile.split("\\*");
                int startIndex = 0;
                for (String excludeFilePart : excludeFileParts) {
                    int currentIndex = modpackFile.indexOf(excludeFilePart, startIndex);
                    if (currentIndex == -1) {
                        return false;
                    }
                    startIndex = currentIndex + excludeFilePart.length();
                }
                return true;
            } else {
                return excludeFile.contains(modpackFile);
            }
        }
    }
}