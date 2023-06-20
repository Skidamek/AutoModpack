/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.modpack;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import pl.skidam.automodpack.loaders.Loader;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.JarUtilities;
import pl.skidam.automodpack.utils.ModpackContentTools;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static pl.skidam.automodpack.StaticVariables.*;


public class Modpack {
    public static Path hostModpackDir = new File(automodpackDir + File.separator + "host-modpack").toPath();
    static Path hostModpackMods = new File(hostModpackDir + File.separator + "mods").toPath();
    public static File hostModpackContentFile = new File(hostModpackDir + File.separator + "modpack-content.json");
    public static final int MAX_MODPACK_ADDITIONS = 10; // at the same time
    private static ExecutorService CREATION_EXECUTOR;
    public static List<CompletableFuture<Void>> creationFutures = new ArrayList<>();
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

    private static void autoExcludeServerMods(List<Jsons.ModpackContentFields.ModpackContentItems> list) {

        if (Loader.getPlatformType() == Loader.ModPlatform.FORGE) return;

        List<String> removeSimilar = new ArrayList<>();

        Collection modList = Loader.getModList();

        for (Object mod : modList) {
            String modId = mod.toString().split(" ")[0]; // mod is  "modid (version)" so we remove everything after space to get modid (modid can't have space in it)
            String modEnv = Loader.getModEnvironment(modId).toUpperCase();
//            LOGGER.warn("Mod {} has environment {}", modId, modEnv);
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

    private static void removeAutoModpackFilesFromContent(List<Jsons.ModpackContentFields.ModpackContentItems> list) {
        list.removeIf(modpackContentItems -> modpackContentItems.file.toLowerCase().contains("automodpack"));
    }

    public static class Content {
        public static Jsons.ModpackContentFields modpackContent;

        public static void create(Path modpackDir, File modpackContentFile) {
            try {
                List<Jsons.ModpackContentFields.ModpackContentItems> list = Collections.synchronizedList(new ArrayList<>());

                ThreadFactory threadFactoryDownloads = new ThreadFactoryBuilder()
                        .setNameFormat("AutoModpackCreation-%d")
                        .build();

                CREATION_EXECUTOR = Executors.newFixedThreadPool(
                        MAX_MODPACK_ADDITIONS,
                        threadFactoryDownloads
                );

                if (serverConfig.syncedFiles.size() > 0) {
                    for (String file : serverConfig.syncedFiles) {
                        LOGGER.info("Syncing {}... ", file);
                        File fileToSync = new File("." + file);

                        if (fileToSync.isDirectory()) {
                            addAllContent(fileToSync, list);
                        } else {
                            addContent(fileToSync.getParentFile(), fileToSync, list);
                        }
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

                modpackContent = new Jsons.ModpackContentFields(null, list);
                modpackContent.version = MC_VERSION;
                modpackContent.modpackName = serverConfig.modpackName;
                modpackContent.loader = Loader.getPlatformType().toString().toLowerCase();
                modpackContent.modpackHash = CustomFileUtils.getHashFromStringOfHashes(ModpackContentTools.getStringOfAllHashes(modpackContent));

                ConfigTools.saveConfig(modpackContentFile, modpackContent);

                HttpServer.filesList.clear();
                for (Jsons.ModpackContentFields.ModpackContentItems item : list) {
                    HttpServer.filesList.add(item.file);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        private static void addAllContent(File modpackDir, List<Jsons.ModpackContentFields.ModpackContentItems> list) throws ExecutionException, InterruptedException {
            if (!modpackDir.exists() || modpackDir.listFiles() == null) return;

            File[] modpackDirFiles = modpackDir.listFiles();
            if (modpackDirFiles == null) return;

            for (File file : modpackDirFiles) {

                while (creationFutures.size() >= MAX_MODPACK_ADDITIONS) { // Async Setting - max `some` fetches at the same time
                    creationFutures = creationFutures.stream()
                            .filter(future -> !future.isDone())
                            .collect(Collectors.toList());
                }

                creationFutures.add(addContentAsync(modpackDir, file, list));
            }

            CompletableFuture.allOf(creationFutures.toArray(new CompletableFuture[0])).get();
        }

        private static CompletableFuture<Void> addContentAsync(File modpackDir, File file, List<Jsons.ModpackContentFields.ModpackContentItems> list) {
            return CompletableFuture.runAsync(() -> {
                try {
                    addContent(modpackDir, file, list);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, CREATION_EXECUTOR);
        }

        public static String removeBeforePattern(String input, String pattern) {
            int index = input.indexOf(pattern);
            if (index != -1) {
                return input.substring(index);
            }
            return input;
        }

        private static void addContent(File modpackDir, File file, List<Jsons.ModpackContentFields.ModpackContentItems> list) throws Exception {
            modpackDir = new File(modpackDir.toPath().normalize().toString());
            if (file.isDirectory()) {
                if (file.getName().startsWith(".")) {
                    return;
                }

                File[] childFiles = file.listFiles();
                if (childFiles == null) return;

                for (File childFile : childFiles) {
                    addContent(modpackDir, childFile, list);
                }
            } else if (file.isFile()) {
                if (file.equals(hostModpackContentFile)) {
                    return;
                }
                Path modpackPath = file.toPath().toAbsolutePath().normalize();
                String modpackFile = modpackPath.toString().replace(hostModpackDir.toAbsolutePath().normalize().toString(), "").replace("\\", "/");
                if (modpackFile.charAt(0) == '.') modpackFile = modpackFile.substring(1);
                String size = String.valueOf(file.length());
                String type = "other";
                String modId = null;
                String version = null;
                boolean isEditable = false;

                if (!modpackDir.toString().startsWith(hostModpackDir.normalize().toString())) {

                    modpackFile = removeBeforePattern(modpackFile, "/" + modpackDir);

                    boolean excluded = false;
                    for (String excludeFile : serverConfig.excludeSyncedFiles) {
                        if (matchesExclusionCriteria(modpackFile, excludeFile)) { // wild cards e.g. *.json or supermod-1.19-*.jar
                            excluded = true;
                            break;
                        }
                    }
                    if (excluded) {
                        LOGGER.info("File {} is excluded! Skipping...", modpackFile);
                        return;
                    }
                }

                File actualFile = new File(modpackFile);
                if (actualFile.toString().startsWith(".")) {
                    LOGGER.warn("Skipping file {}", modpackFile);
                    return;
                }

                if (size.equals("0")) {
                    LOGGER.warn("File {} is empty! Skipping...", modpackFile);
                    return;
                }

                if (!modpackDir.equals(hostModpackDir.toFile())) {
                    if (modpackFile.endsWith(".tmp")) {
                        LOGGER.warn("File {} is temporary! Skipping...", modpackFile);
                        return;
                    }

                    if (modpackFile.endsWith(".disabled")) {
                        LOGGER.warn("File {} is disabled! Skipping...", modpackFile);
                        return;
                    }

                    if (modpackFile.endsWith(".bak")) {
                        LOGGER.warn("File {} is backup file, unnecessary on client! Skipping...", modpackFile);
                        return;
                    }
                }

                String sha1 = CustomFileUtils.getHashWithRetry(file, "SHA-1");
                String murmurHash = null;

                if (file.getName().endsWith(".jar")) {
                    modId = JarUtilities.getModIdFromJar(file, true);
                    type = modId == null ? "other" : "mod";
                    if (type.equals("mod")) {
                        version = JarUtilities.getModVersion(file);
                        murmurHash = CustomFileUtils.getHashWithRetry(file, "murmur");
                    }
                }

                if (type.equals("other")) {
                    if (modpackFile.startsWith("/config/")) {
                        type = "config";
                    } else if (modpackFile.startsWith("/shaderpacks/")) {
                        type = "shaderpack";
                        murmurHash = CustomFileUtils.getHashWithRetry(file, "murmur");
                    } else if (modpackFile.startsWith("/resourcepacks/")) {
                        type = "resourcepack";
                        murmurHash = CustomFileUtils.getHashWithRetry(file, "murmur");
                    } else if (modpackFile.endsWith("/options.txt")) {
                        type = "mc_options";
                    }
                }

                for (String editableFile : serverConfig.allowEditsInFiles) {
                    if (modpackFile.equals(editableFile)) {
                        isEditable = true;
                        LOGGER.info("File {} is editable!", modpackFile);
                        break;
                    }
                }

                // It should overwrite existing file in the list
                // because first this syncs files from server running dir
                // And then it gets files from host-modpack dir
                // So we want to overwrite files from server running dir with files from host-modpack dir
                // if there are likely same or a bit changed
                List<Jsons.ModpackContentFields.ModpackContentItems> copyList = new ArrayList<>(list);
                for (Jsons.ModpackContentFields.ModpackContentItems item : copyList) {
                    if (item.file.equals(modpackFile)) {
                        list.remove(item);
                        break;
                    }
                }

                String link = modpackFile;

                list.add(new Jsons.ModpackContentFields.ModpackContentItems(modpackFile, link, size, type, isEditable, modId, version, sha1, murmurHash));
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

    public static class ModpackObject {
        private String NAME;
        private String LINK;
        private String LOADER;
        private String VERSION;
        private String HASH;
        private List<Jsons.ModpackContentFields.ModpackContentItems> CONTENT;

        public String getName() { return NAME; }
        public String getLink() { return LINK; }
        public String getLoader() { return LOADER; }
        public String getVersion() { return VERSION; }
        public String getHash() { return HASH; }
        public List<Jsons.ModpackContentFields.ModpackContentItems> getContent() { return CONTENT; }

        public void setName(String name) { NAME = name; }
        public void setLink(String link) { LINK = link; }
        public void setLoader(String loader) { LOADER = loader; }
        public void setVersion(String version) { VERSION = version; }
        public void setHash(String hash) { HASH = hash; }
        public void setContent(List<Jsons.ModpackContentFields.ModpackContentItems> content) { CONTENT = content; }
    }

    public static Map<Path, ModpackObject> getModpacksMap() {

        File[] modpacks = modpacksDir.listFiles();

        if (modpacks == null) {
            LOGGER.error("Failed to list files in modpacks dir!");
            return null;
        }

        Map<Path, ModpackObject> modpacksMap = new HashMap<>();

        for (File modpack : modpacks) {
            if (modpack.isDirectory()) {
                File modpackJson = new File(modpack, hostModpackContentFile.getName());
                if (modpackJson.exists()) {
                    modpacksMap.put(modpack.toPath(), new ModpackObject());
                }
            }
        }

        return modpacksMap;
    }

    public static void setModpackObject(Map<Path, ModpackObject> modpacksMap) {

        if (modpacksMap == null) {
            LOGGER.error("Failed to get modpacks map!");
            return;
        }

        for (Map.Entry<Path, ModpackObject> entry : modpacksMap.entrySet()) {
            Path modpackDir = entry.getKey();
            ModpackObject modpackObject = entry.getValue();

            File modpackJson = new File(modpackDir.toFile(), hostModpackContentFile.getName());

            try {
                Jsons.ModpackContentFields modpackContentFields = ConfigTools.GSON.fromJson(new FileReader(modpackJson), Jsons.ModpackContentFields.class);

                modpackObject.setName(modpackContentFields.modpackName);
                modpackObject.setLink(modpackContentFields.link);
                modpackObject.setLoader(modpackContentFields.loader);
                modpackObject.setVersion(modpackContentFields.version);
                modpackObject.setHash(modpackContentFields.modpackHash);
                modpackObject.setContent(modpackContentFields.list);

            } catch (IOException e) {
                LOGGER.error("Failed to read modpack content file {}", modpackJson, e);
            }
        }
    }
}