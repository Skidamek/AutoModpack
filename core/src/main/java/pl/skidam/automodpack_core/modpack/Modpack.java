package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Modpack {
    public final static ThreadPoolExecutor CREATION_EXECUTOR = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() * 2), new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build());
    public static List<Modpack.Content> modpacks = Collections.synchronizedList(new ArrayList<>());
    public boolean generate() {
        if (isGenerating()) {
            LOGGER.error("Called generate() twice!");
            return false;
        }

        try {
            if (!Files.exists(hostContentModpackDir)) {
                Files.createDirectories(hostContentModpackDir);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        var content = new Content(serverConfig.modpackName, serverConfig.syncedFiles, serverConfig.allowEditsInFiles);
        return content.create(Path.of(System.getProperty("user.dir")), hostContentModpackDir);
    }

    public static boolean isGenerating() {
        int activeCount = CREATION_EXECUTOR.getActiveCount();
        int queueSize = CREATION_EXECUTOR.getQueue().size();
        return activeCount > 0 || queueSize > 0;
    }

    public static void shutdownExecutor() {
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
    }


    public static class Content {
        public Jsons.ModpackContentFields modpackContent;
        public final List<Jsons.ModpackContentFields.ModpackContentItem> list = Collections.synchronizedList(new ArrayList<>());
        public final ObservableMap<String, Path> pathsMap = new ObservableMap<>();
        private final List<CompletableFuture<Void>> creationFutures = Collections.synchronizedList(new ArrayList<>());
        private final String modpackName;
        private final WildCards syncedFilesWildCards;
        private final WildCards allowEditsWildCards;

        public Content(String modpackName, List<String> syncedFiles, List<String> allowEditsInFiles) {
            this.modpackName = modpackName;
//            this.syncedFilesWildCards = new WildCards(Stream.concat(syncedFiles.stream(), Stream.of("!/automodpack/*")).toList());
            this.syncedFilesWildCards = new WildCards(syncedFiles);
            this.allowEditsWildCards = new WildCards(allowEditsInFiles);
        }

        public boolean create(Path cwd, Path modpackDir) {
            try {
                pathsMap.clear();

                if (modpackDir != null) {
                    LOGGER.info("Syncing {}...", modpackDir.getFileName());
                    Files.list(modpackDir).forEach((path ->  creationFutures.add(generateAsync(path.getParent(), path))));

                    // Wait till finish
                    creationFutures.forEach((CompletableFuture::join));
                    creationFutures.clear();
                }

                syncedFilesWildCards.getWildcardMatches().values().forEach(path -> creationFutures.add(generateAsync(cwd, path)));

                // Wait till finish
                creationFutures.forEach((CompletableFuture::join));
                creationFutures.clear();

                if (list.isEmpty()) {
                    LOGGER.warn("Modpack is empty!");
                    return false;
                }
            } catch (Exception e) {
                LOGGER.error("Error while generating modpack!", e);
                return false;
            }

            saveModpackContent();
            if (httpServer != null) {
                httpServer.addPaths(pathsMap);
            }
            modpacks.add(this);

            return true;
        }

        // This is important to make it synchronized otherwise it could corrupt the file and crash
        public synchronized void saveModpackContent() {
            synchronized (list) {
                modpackContent = new Jsons.ModpackContentFields(null, list);

                // TODO assign versions to these variables from http config
                modpackContent.automodpackVersion = AM_VERSION;
                modpackContent.mcVersion = MC_VERSION;
                modpackContent.loaderVersion = LOADER_VERSION;
                modpackContent.loader = LOADER;
                modpackContent.modpackName = modpackName;

                ConfigTools.saveConfig(hostModpackContentFile, modpackContent);
            }
        }

        private CompletableFuture<Void> generateAsync(Path modpackDir, Path file) {
            return CompletableFuture.runAsync(() -> generate(modpackDir, file), CREATION_EXECUTOR);
        }

        private void generate(Path modpackDir, Path file) {
            try {
                Jsons.ModpackContentFields.ModpackContentItem item = generateContent(modpackDir, file);
                if (item != null && !list.contains(item)) {
                    LOGGER.info("generated content for {}", item.file);
                    list.add(item);
                }
            } catch (Exception e) {
                LOGGER.error("Error while generating content for: " + file + " generated from: " + modpackDir, e);
            }
        }

        public CompletableFuture<Void> replaceAsync(Path modpackDir, Path file) {
            return CompletableFuture.runAsync(() -> replace(modpackDir, file), CREATION_EXECUTOR);
        }

        public void replace(Path modpackDir, Path file) {
            remove(file, false);
            generate(modpackDir, file);
        }

        public void remove(Path file, boolean save) {

            String modpackFile = CustomFileUtils.formatPath(file);

            synchronized (list) {
                for (Jsons.ModpackContentFields.ModpackContentItem item : this.list) {
                    if (item.file.equals(modpackFile)) {
                        this.pathsMap.remove(item.sha1);
                        this.list.remove(item);
                        LOGGER.info("Removed content for {}", modpackFile);
                        break;
                    }
                }
            }

            if (save) {
                saveModpackContent();
            }
        }

        private boolean internalFile(Path file) {
            // check if file is any path from global variables if so return true besides `hostContentModpackDir`
            return file.equals(automodpackDir) || file.equals(hostModpackDir) || file.equals(hostModpackContentFile) || file.equals(modpacksDir) || file.equals(clientConfigFile) || file.equals(serverConfigFile);
        }

        private Jsons.ModpackContentFields.ModpackContentItem generateContent(Path modpackDir, final Path file) throws Exception {
            if (internalFile(file)) {
                return null;
            }

            if (modpackDir != null) {
                modpackDir = modpackDir.toAbsolutePath().normalize();
            }

            if (Files.isDirectory(file)) {
                if (file.getFileName().toString().startsWith(".")) {
                    LOGGER.info("Skipping " + file.getFileName() + " because it starts with a dot");
                    return null;
                }

                List<Path> childFiles = Files.list(file).toList();

                for (Path childFile : childFiles) {
                    // FIXME concerning
                    var generated = generateContent(modpackDir, childFile);
                    if (generated != null) {
                        list.add(generated);
                    }
                }
            } else {
                String modpackFile = CustomFileUtils.formatPath(file);

                boolean isEditable = false;

                final String size = String.valueOf(Files.size(file));

                if (size.equals("0")) {
                    LOGGER.info("Skipping file {} because it is empty", modpackFile);
                    return null;
                }

                if (modpackDir == null) {
                    modpackFile = "/" + file.getFileName();
                } else if (!modpackDir.toString().startsWith(hostContentModpackDir.normalize().toString())) {
                    modpackFile = "/" + modpackDir.relativize(file.toAbsolutePath().normalize());
                }

                modpackFile = modpackFile.replace(File.separator, "/");

                // this is the case when the matching 'excluded' file is found inside the automodpack dir
//                if (syncedFilesWildCards.fileBlackListed(file)) {
//                    LOGGER.info("File {} is blacklisted! Skipping...", modpackFile);
//                    return null;
//                }

                if (!hostContentModpackDir.equals(modpackDir)) {
                    if (file.toString().startsWith(".")) {
                        LOGGER.info("Skipping file {} is hidden", modpackFile);
                        return null;
                    }

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

                String sha1 = CustomFileUtils.getHash(file, "SHA-1").orElseThrow();

                String type = FileInspection.isMod(file) ? "mod" : "other";

                if (type.equals("other")) {
                    if (modpackFile.contains("/config/")) {
                        type = "config";
                    } else if (modpackFile.contains("/shaderpacks/")) {
                        type = "shader";
                    } else if (modpackFile.contains("/resourcepacks/")) {
                        type = "resourcepack";
                    } else if (modpackFile.endsWith("/options.txt")) {
                        type = "mc_options";
                    }
                }

                // Exclude automodpack mod
                if (type.equals("mod") && (MOD_ID + "-bootstrap").equals(FileInspection.getModID(file))) {
                    return null;
                }

                // For CF API
                String murmur = null;
                if (type.equals("mod") || type.equals("shader") || type.equals("resourcepack")) {
                    murmur = CustomFileUtils.getHash(file, "murmur").orElseThrow();
                }


                if (allowEditsWildCards.fileMatches(modpackFile, file)) {
                    isEditable = true;
                    LOGGER.info("File {} is editable!", modpackFile);
                }


                // TODO re-add this feature since now it does not seem to make any sense when we are generating this async
//                // It should overwrite existing file in the list
//                // because first this syncs files from server running dir
//                // And then it gets files from host-modpack dir,
//                // So we want to overwrite files from server running dir with files from host-modpack dir
//                // if there are likely same or a bit changed
//                for (Jsons.ModpackContentFields.ModpackContentItem item : list) {
//                    if (item.file.equals(modpackFile) && item.sha1.equals(sha1)) {
//                        list.remove(item);
//                        pathsMap.remove(item.sha1);
//                        break;
//                    }
//                }

                // Add to path to then add it to the file checker

                pathsMap.put(sha1, file);

                return new Jsons.ModpackContentFields.ModpackContentItem(modpackFile, size, type, isEditable, sha1, murmur);
            }

            return null;
        }
    }
}