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
import pl.skidam.automodpack.utils.FileChangeChecker;
import pl.skidam.automodpack.utils.JarUtilities;
import pl.skidam.automodpack.utils.ModpackContentTools;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import static pl.skidam.automodpack.GlobalVariables.*;

public class Modpack {
    public static Path hostModpackDir = Paths.get(automodpackDir + File.separator + "host-modpack");
    static Path hostModpackMods = Paths.get(hostModpackDir + File.separator + "mods");
    public static Path hostModpackContentFile = Paths.get(hostModpackDir + File.separator + "modpack-content.json");
    public static final int MAX_MODPACK_ADDITIONS = 8; // at the same time
    private static ExecutorService CREATION_EXECUTOR;
    public static boolean generate() {
        try {
            if (!Files.exists(hostModpackDir)) {
                Files.createDirectories(hostModpackDir);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return Content.create(hostModpackDir);
    }

    private static void autoExcludeServerMods(List<Jsons.ModpackContentFields.ModpackContentItem> list) {

        if (Loader.getPlatformType() == Loader.ModPlatform.FORGE) return;

        List<String> removeSimilar = new ArrayList<>();

        Collection modList = Loader.getModList();

        for (Object mod : modList) {
            String modId = mod.toString().split(" ")[0]; // mod is  "modid (version)" so we remove everything after space to get modid (modid can't have space in it)
            String modEnv = Loader.getModEnvironment(modId).toUpperCase();
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
                Path contentFile = Paths.get(hostModpackMods + File.separator + modpackContentItems.file);
                String contentFileName = String.valueOf(contentFile.getFileName());
                if (contentFileName.contains(modId)) {
                    LOGGER.info("File {} has been auto excluded from modpack because mod of this file is already excluded", contentFileName);
                    return true;
                }
                return false;
            });
        }
    }

    private static void removeAutoModpackFilesFromContent(List<Jsons.ModpackContentFields.ModpackContentItem> list) {
        list.removeIf(modpackContentItems -> modpackContentItems.file.toLowerCase().contains("automodpack"));
    }

    public static class Content {
        public static Jsons.ModpackContentFields modpackContent;
        public static List<Jsons.ModpackContentFields.ModpackContentItem> list = Collections.synchronizedList(new ArrayList<>());

        public static boolean create(Path modpackDir) {

            try {
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
                        Path fileToSync = Paths.get("." + file);

                        if (Files.isDirectory(fileToSync)) {
                            addAllContent(fileToSync, list);
                        } else {
                            Jsons.ModpackContentFields.ModpackContentItem content = generateContent(fileToSync.getParent(), fileToSync, list);
                            if (content != null) {
                                list.add(content);
                            }
                        }
                    }
                }

                LOGGER.info("Syncing {}...", modpackDir.getFileName());
                addAllContent(modpackDir, list);

                if (list.size() == 0) {
                    LOGGER.warn("Modpack is empty! Nothing to generate!");
                    return false;
                }

                removeAutoModpackFilesFromContent(list);
                if (serverConfig.autoExcludeServerSideMods) {
                    autoExcludeServerMods(list);
                }

                CREATION_EXECUTOR.shutdown();
                try {
                    if (!CREATION_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                        CREATION_EXECUTOR.shutdownNow();
                        if (!CREATION_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                            LOGGER.error("CREATION Executor did not terminate");
                        }
                    }
                } catch (InterruptedException e) {
                    CREATION_EXECUTOR.shutdownNow();
                }

            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }

            saveModpackContent();

            if (HttpServer.fileChangeChecker != null) {
                HttpServer.fileChangeChecker.stopChecking();
            }

            HttpServer.listOfPaths.clear();
            for (Jsons.ModpackContentFields.ModpackContentItem item : list) {
                Path filePath = Paths.get(hostModpackDir + File.separator + item.file);
                if (!Files.exists(filePath)) {
                    filePath = Paths.get("." + item.file);
                }

                if (Files.exists(filePath)) {
                    HttpServer.listOfPaths.add(filePath);
                } else {
                    LOGGER.error("File {} doesn't exist!", item.file);
                }
            }

            if (HttpServer.fileChangeChecker == null) {
                HttpServer.fileChangeChecker = new FileChangeChecker(HttpServer.listOfPaths);
            }

            HttpServer.fileChangeChecker.startChecking();

            return true;
        }

        public static void saveModpackContent() {
            modpackContent = new Jsons.ModpackContentFields(null, list);
            modpackContent.automodpackVersion = AM_VERSION;
            modpackContent.mcVersion = MC_VERSION;
            modpackContent.modpackName = serverConfig.modpackName;
            modpackContent.loader = Loader.getPlatformType().toString().toLowerCase();
            modpackContent.loaderVersion = Loader.getLoaderVersion();
            modpackContent.modpackHash = CustomFileUtils.getHashFromStringOfHashes(ModpackContentTools.getStringOfAllHashes(modpackContent));

            ConfigTools.saveConfig(hostModpackContentFile, modpackContent);
        }


        private static void addAllContent(Path modpackDir, List<Jsons.ModpackContentFields.ModpackContentItem> list) throws ExecutionException, InterruptedException, IOException {
            if (!Files.exists(modpackDir) || !Files.isDirectory(modpackDir)) return;

            try (DirectoryStream<Path> modpackDirStream = Files.newDirectoryStream(modpackDir)) {
                List<CompletableFuture<Void>> creationFutures = new ArrayList<>();

                for (Path file : modpackDirStream) {
                    while (creationFutures.size() >= MAX_MODPACK_ADDITIONS) { // Async Setting - max `some` additions at the same time
                        creationFutures.removeIf(CompletableFuture::isDone);
                    }

                    creationFutures.add(addContentAsync(modpackDir, file, list));
                }

                CompletableFuture.allOf(creationFutures.toArray(new CompletableFuture[0])).get();
            }
        }

        private static CompletableFuture<Void> addContentAsync(Path modpackDir, Path file, List<Jsons.ModpackContentFields.ModpackContentItem> list) {
            return CompletableFuture.runAsync(() -> {
                try {
                    Jsons.ModpackContentFields.ModpackContentItem content = generateContent(modpackDir, file, list);
                    if (content != null) {
                        list.add(content);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, CREATION_EXECUTOR);
        }

        public static void replaceOneItem(Path modpackDir, Path file, List<Jsons.ModpackContentFields.ModpackContentItem> list) {
            // remove the old one
            removeOneItem(file, list);

            // generate content of the file and add it to the list
            try {
                Jsons.ModpackContentFields.ModpackContentItem content = generateContent(modpackDir, file.normalize(), list);
                if (content != null) {
                    list.add(content);
                } else {
                    LOGGER.error("Failed to generate content for {}!", file);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            Modpack.Content.list = list;
            saveModpackContent();
        }

        public static void removeOneItem(Path file, List<Jsons.ModpackContentFields.ModpackContentItem> list) {

            // remove hostModpackDir from the path if it is there
            Path modpackPath = file.toAbsolutePath().normalize();
            String fileString = modpackPath.toString().replace(hostModpackDir.toAbsolutePath().normalize().toString(), "").replace("\\", "/");
            if (fileString.charAt(0) == '.') {
                fileString = fileString.substring(1);
            }

            // go through all items and remove the one that has the same file path
            for (Jsons.ModpackContentFields.ModpackContentItem item : list) {
                if (item.file.equals(fileString)) {
                    list.remove(item);
                    break;
                }
            }

            Modpack.Content.list = list;
            saveModpackContent();
        }

        public static String removeBeforePattern(String input, String pattern) {
            int index = input.indexOf(pattern.replaceAll("\\\\", "/")); // fix for a windows path system
            if (index != -1) {
                return input.substring(index);
            }
            return input;
        }

        private static Jsons.ModpackContentFields.ModpackContentItem generateContent(Path modpackDir, Path file, List<Jsons.ModpackContentFields.ModpackContentItem> list) throws Exception {
            modpackDir = modpackDir.normalize();
            if (Files.isDirectory(file)) {
                if (file.getFileName().toString().startsWith(".")) {
                    LOGGER.info("Skipping " + file.getFileName() + " because it starts with a dot");
                    return null;
                }

                Path[] childFiles = Files.list(file).toArray(Path[]::new);

                for (Path childFile : childFiles) {
                    Jsons.ModpackContentFields.ModpackContentItem content = generateContent(modpackDir, childFile, list);
                    if (content != null) {
                        list.add(content);
                    }
                }
            } else if (Files.isRegularFile(file)) {
                if (file.equals(hostModpackContentFile)) {
                    return null;
                }
                Path modpackPath = file.toAbsolutePath().normalize();
                String modpackFile = modpackPath.toString().replace(hostModpackDir.toAbsolutePath().normalize().toString(), "").replace("\\", "/");
                if (modpackFile.charAt(0) == '.') {
                    modpackFile = modpackFile.substring(1);
                }
                String size = String.valueOf(Files.size(file));
                String type = "other";
                String modId = null;
                String version = null;
                boolean isEditable = false;

                if (!modpackDir.toString().startsWith(hostModpackDir.normalize().toString())) {

                    modpackFile = removeBeforePattern(modpackFile, "/" + modpackDir);

                    boolean excluded = false;
                    for (String excludeFile : serverConfig.excludeSyncedFiles) {
                        if (Content.matchesWildCardCriteria(modpackFile, excludeFile)) { // wild cards e.g. *.json or supermod-1.19-*.jar
                            excluded = true;
                            break;
                        }
                    }
                    if (excluded) {
                        LOGGER.info("File {} is excluded! Skipping...", modpackFile);
                        return null;
                    }
                }

                Path actualFile = Paths.get(modpackFile);
                if (actualFile.toString().startsWith(".")) {
                    LOGGER.info("Skipping file {}", modpackFile);
                    return null;
                }

                if (size.equals("0")) {
                    LOGGER.info("File {} is empty! Skipping...", modpackFile);
                    return null;
                }

                if (!modpackDir.equals(hostModpackDir)) {
                    if (modpackFile.endsWith(".tmp")) {
                        LOGGER.info("File {} is temporary! Skipping...", modpackFile);
                        return null;
                    }

                    if (modpackFile.endsWith(".disabled")) {
                        LOGGER.info("File {} is disabled! Skipping...", modpackFile);
                        return null;
                    }

                    if (modpackFile.endsWith(".bak")) {
                        LOGGER.info("File {} is backup file, unnecessary on client! Skipping...", modpackFile);
                        return null;
                    }
                }

                String sha1 = CustomFileUtils.getHash(file, "SHA-1");
                String murmur = null;

                if (file.getFileName().toString().endsWith(".jar")) {
                    modId = JarUtilities.getModIdFromJar(file, true);
                    type = modId == null ? "other" : "mod";
                    if (type.equals("mod")) {
                        version = JarUtilities.getModVersion(file);
                        murmur = CustomFileUtils.getHash(file, "murmur");
                    }
                }

                if (type.equals("other")) {
                    if (modpackFile.contains("/config/")) {
                        type = "config";
                    } else if (modpackFile.contains("/shaderpacks/")) {
                        type = "shader";
                        murmur = CustomFileUtils.getHash(file, "murmur");
                    } else if (modpackFile.contains("/resourcepacks/")) {
                        type = "resourcepack";
                        murmur = CustomFileUtils.getHash(file, "murmur");
                    } else if (modpackFile.endsWith("/options.txt")) {
                        type = "mc_options";
                    }
                }


                for (String editableFile : serverConfig.allowEditsInFiles) {
                    if (Content.matchesWildCardCriteria(modpackFile, editableFile)) {
                        isEditable = true;
                        LOGGER.info("File {} is editable!", modpackFile);
                        break;
                    }
                }

                // It should overwrite existing file in the list
                // because first this syncs files from server running dir
                // And then it gets files from host-modpack dir,
                // So we want to overwrite files from server running dir with files from host-modpack dir
                // if there are likely same or a bit changed
                List<Jsons.ModpackContentFields.ModpackContentItem> copyList = new ArrayList<>(list);
                for (Jsons.ModpackContentFields.ModpackContentItem item : copyList) {
                    if (item.file.equals(modpackFile)) {
                        list.remove(item);
                        break;
                    }
                }

                String link = modpackFile;

                return new Jsons.ModpackContentFields.ModpackContentItem(modpackFile, link, size, type, isEditable, modId, version, sha1, murmur);
            }

            return null;
        }

        private static boolean matchesWildCardCriteria(String modpackFile, String wildCardString) {
            if (wildCardString.contains("*")) { // wild cards magic
                String[] excludeFileParts = wildCardString.split("\\*");
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
                return wildCardString.equals(modpackFile);
            }
        }
    }

    public static class ModpackObject {
        private String NAME;
        private String LINK;
        private String LOADER;
        private String VERSION;
        private String HASH;
        private List<Jsons.ModpackContentFields.ModpackContentItem> CONTENT;

        public String getName() { return NAME; }
        public String getLink() { return LINK; }
        public String getLoader() { return LOADER; }
        public String getVersion() { return VERSION; }
        public String getHash() { return HASH; }
        public List<Jsons.ModpackContentFields.ModpackContentItem> getContent() { return CONTENT; }

        public void setName(String name) { NAME = name; }
        public void setLink(String link) { LINK = link; }
        public void setLoader(String loader) { LOADER = loader; }
        public void setVersion(String version) { VERSION = version; }
        public void setHash(String hash) { HASH = hash; }
        public void setContent(List<Jsons.ModpackContentFields.ModpackContentItem> content) { CONTENT = content; }
    }

    public static Map<Path, ModpackObject> getModpacksMap() {
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(modpacksDir)) {
            Map<Path, ModpackObject> modpacksMap = new HashMap<>();

            for (Path modpack : directoryStream) {
                if (Files.isDirectory(modpack)) {
                    Path modpackJson = modpack.resolve(hostModpackContentFile.getFileName());
                    if (Files.exists(modpackJson)) {
                        modpacksMap.put(modpack, new ModpackObject());
                    }
                }
            }

            return modpacksMap;
        } catch (IOException e) {
            LOGGER.error("Failed to list files in modpacks dir!", e);
            return null;
        }
    }

    public static void setModpackObject(Map<Path, ModpackObject> modpacksMap) {

        if (modpacksMap == null) {
            LOGGER.error("Failed to get modpacks map!");
            return;
        }

        for (Map.Entry<Path, ModpackObject> entry : modpacksMap.entrySet()) {
            Path modpackDir = entry.getKey();
            ModpackObject modpackObject = entry.getValue();

            Path modpackJsonPath = modpackDir.resolve(hostModpackContentFile.getFileName());

            try {
                Jsons.ModpackContentFields modpackContentFields = ConfigTools.GSON.fromJson(Files.newBufferedReader(modpackJsonPath), Jsons.ModpackContentFields.class);

                modpackObject.setName(modpackContentFields.modpackName);
                modpackObject.setLink(modpackContentFields.link);
                modpackObject.setLoader(modpackContentFields.loader);
                modpackObject.setVersion(modpackContentFields.mcVersion);
                modpackObject.setHash(modpackContentFields.modpackHash);
                modpackObject.setContent(modpackContentFields.list);

            } catch (IOException e) {
                LOGGER.error("Failed to read modpack content file {}", modpackJsonPath, e);
            }
        }
    }
}