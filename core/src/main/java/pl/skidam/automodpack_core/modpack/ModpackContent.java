package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static pl.skidam.automodpack_core.Constants.*;

public class ModpackContent {
    public final Set<Jsons.ModpackContentFields.ModpackContentItem> list = ConcurrentHashMap.newKeySet();
    public final ObservableMap<String, Path> pathsMap = new ObservableMap<>();
    private final String MODPACK_NAME;
    private final FileTreeScanner SYNCED_FILES_CARDS;
    private final FileTreeScanner EDITABLE_CARDS;
    private final FileTreeScanner FORCE_COPY_FILES_TO_STANDARD_LOCATION;
    private final Path MODPACK_DIR;
    private final ThreadPoolExecutor CREATION_EXECUTOR;
    private final FileMetadataCache metadataCache;
    private final Map<String, String> sha1MurmurMapPreviousContent = new HashMap<>();

    public ModpackContent(String modpackName, Path cwd, Path modpackDir, Set<String> syncedFiles, Set<String> allowEditsInFiles, Set<String> forceCopyFilesToStandardLocation, ThreadPoolExecutor CREATION_EXECUTOR, FileMetadataCache metadataCache) {
        this.MODPACK_NAME = modpackName;
        this.MODPACK_DIR = modpackDir;
        this.CREATION_EXECUTOR = CREATION_EXECUTOR;
        this.metadataCache = metadataCache;
        Set<Path> directoriesToSearch = new HashSet<>(2);
        if (MODPACK_DIR != null) directoriesToSearch.add(MODPACK_DIR);
        if (cwd != null) {
            directoriesToSearch.add(cwd);
            this.SYNCED_FILES_CARDS = new FileTreeScanner(syncedFiles, Set.of(cwd));
        } else {
            this.SYNCED_FILES_CARDS = new FileTreeScanner(syncedFiles, Set.of());
        }
        this.EDITABLE_CARDS = new FileTreeScanner(allowEditsInFiles, directoriesToSearch);
        this.FORCE_COPY_FILES_TO_STANDARD_LOCATION = new FileTreeScanner(forceCopyFilesToStandardLocation, directoriesToSearch);
    }

    public String getModpackName() {
        return MODPACK_NAME;
    }

    public boolean create() {
        Set<Jsons.ModpackContentFields.FileToDelete> computedFilesToDelete = new HashSet<>();

        try {
            SYNCED_FILES_CARDS.scan();
            EDITABLE_CARDS.scan();
            FORCE_COPY_FILES_TO_STANDARD_LOCATION.scan();

            pathsMap.clear();
            sha1MurmurMapPreviousContent.clear();

            getPreviousContent().ifPresent(previousContent -> {
                Map<String, Jsons.ModpackContentFields.FileToDelete> oldFilesMap = previousContent.nonModpackFilesToDelete.stream()
                        .collect(Collectors.toMap(f -> f.file, f -> f, (a, b) -> a));

                if (serverConfig != null && serverConfig.nonModpackFilesToDelete != null) {
                    for (var fileToDeleteEntry : serverConfig.nonModpackFilesToDelete.entrySet()) {
                        var file = fileToDeleteEntry.getKey();
                        var sha1 = fileToDeleteEntry.getValue();
                        if (oldFilesMap.containsKey(file) && oldFilesMap.get(file).sha1.equalsIgnoreCase(sha1)) {
                            computedFilesToDelete.add(oldFilesMap.get(file));
                        } else {
                            String currentTimestamp = String.valueOf(System.currentTimeMillis());
                            computedFilesToDelete.add(new Jsons.ModpackContentFields.FileToDelete(file, sha1, currentTimestamp));
                        }
                    }
                }

                previousContent.list.forEach(item -> sha1MurmurMapPreviousContent.put(item.sha1, item.murmur));
            });

            Map<String, Path> filesToProcess = new HashMap<>();

            SYNCED_FILES_CARDS.getMatchedPaths().values().forEach(path -> filesToProcess.put(SmartFileUtils.formatPath(path, MODPACK_DIR), path));

            if (MODPACK_DIR != null) {
                try (Stream<Path> stream = Files.walk(MODPACK_DIR)) { // in case there any files with the same relative path, we prefer from MODPACK_DIR, this will override previous entries
                    stream.forEach(path -> filesToProcess.put(SmartFileUtils.formatPath(path, MODPACK_DIR), path));
                }
            }

            List<CompletableFuture<Jsons.ModpackContentFields.ModpackContentItem>> futures = filesToProcess.entrySet().stream()
                    .map(entry -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return generateContent(entry.getValue(), entry.getKey());
                        } catch (Exception e) {
                            LOGGER.error("Error generating content for {}", entry.getValue(), e);
                            return null;
                        }
                    }, CREATION_EXECUTOR))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (var future : futures) {
                Jsons.ModpackContentFields.ModpackContentItem item = future.join();
                if (item != null) {
                    list.add(item);
                    pathsMap.put(item.sha1, SmartFileUtils.getPathFromCWD(item.file));
                }
            }

            if (list.isEmpty()) {
                LOGGER.warn("Modpack is empty!");
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Error while generating modpack!", e);
            return false;
        }

        saveModpackContent(computedFilesToDelete);
        if (hostServer != null) {
            hostServer.setPaths(pathsMap);
        }

        return true;
    }

    public Optional<Jsons.ModpackContentFields> getPreviousContent() {
        var optionalModpackContentFile = ModpackContentTools.getModpackContentFile(MODPACK_DIR);
        return optionalModpackContentFile.map(ConfigTools::loadModpackContent);
    }

    public boolean loadPreviousContent() {
        var optionalPreviousModpackContent = getPreviousContent();
        if (optionalPreviousModpackContent.isEmpty()) return false;
        Jsons.ModpackContentFields previousModpackContent = optionalPreviousModpackContent.get();

        synchronized (list) {
            list.addAll(previousModpackContent.list);

            for (Jsons.ModpackContentFields.ModpackContentItem modpackContentItem : list) {
                Path file = SmartFileUtils.getPath(MODPACK_DIR, modpackContentItem.file);
                if (!Files.exists(file)) file = SmartFileUtils.getPathFromCWD(modpackContentItem.file);
                if (!Files.exists(file)) {
                    LOGGER.warn("File {} does not exist!", file);
                    continue;
                }

                pathsMap.put(modpackContentItem.sha1, file);
            }
        }

        if (hostServer != null) {
            hostServer.setPaths(pathsMap);
        }

        saveModpackContent(previousModpackContent.nonModpackFilesToDelete);

        return true;
    }

    public synchronized void saveModpackContent(Set<Jsons.ModpackContentFields.FileToDelete> nonModpackFilesToDelete) {
        if (nonModpackFilesToDelete == null) {
            throw new IllegalArgumentException("filesToDelete is null");
        }

        synchronized (list) {
            Jsons.ModpackContentFields modpackContent = new Jsons.ModpackContentFields(list);

            modpackContent.automodpackVersion = AM_VERSION;
            modpackContent.mcVersion = MC_VERSION;
            modpackContent.loaderVersion = LOADER_VERSION;
            modpackContent.loader = LOADER;
            modpackContent.modpackName = MODPACK_NAME;
            modpackContent.nonModpackFilesToDelete = nonModpackFilesToDelete;

            ConfigTools.saveModpackContent(hostModpackContentFile, modpackContent);
        }
    }

    public CompletableFuture<Void> replaceAsync(Path file) {
        return CompletableFuture.runAsync(() -> replace(file), CREATION_EXECUTOR);
    }

    public void replace(Path file) {
        remove(file);
        try {
            Jsons.ModpackContentFields.ModpackContentItem item = generateContent(file, SmartFileUtils.formatPath(file, MODPACK_DIR));
            if (item != null) {
                LOGGER.info("generated content for {}", item.file);
                synchronized (list) {
                    list.add(item);
                }
                pathsMap.put(item.sha1, file);
            }
        } catch (Exception e) {
            LOGGER.error("Error while replacing content for: " + file, e);
        }
    }

    public void remove(Path file) {
        String modpackFile = SmartFileUtils.formatPath(file, MODPACK_DIR);

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
    }

    public static boolean isInnerFile(Path file) {
        Path normalizedFilePath = file.toAbsolutePath().normalize();
        boolean isInner = normalizedFilePath.startsWith(automodpackDir.toAbsolutePath().normalize()) && !normalizedFilePath.startsWith(hostModpackDir.toAbsolutePath().normalize());
        if (!isInner && normalizedFilePath.equals(hostModpackContentFile.toAbsolutePath().normalize())) { // special case, since its inside hostModpackDir
            return true;
        }

        return isInner;
    }

    private Jsons.ModpackContentFields.ModpackContentItem generateContent(final Path file, final String formattedFile) throws Exception {
        if (!Files.isRegularFile(file)) return null;

        if (serverConfig == null) {
            LOGGER.error("Server config is null!");
            return null;
        }

        if (isInnerFile(file)) {
            return null;
        }

        if (formattedFile.startsWith("/automodpack/")) {
            return null;
        }

        final String size = String.valueOf(Files.size(file));

        if (serverConfig.autoExcludeUnnecessaryFiles) {
            if (size.equals("0")) {
                LOGGER.info("Skipping file {} because it is empty", formattedFile);
                return null;
            }

            if (file.getFileName().toString().startsWith(".")) {
                LOGGER.info("Skipping file {} is hidden", formattedFile);
                return null;
            }

            if (formattedFile.endsWith(".tmp")) {
                LOGGER.info("File {} is temporary! Skipping...", formattedFile);
                return null;
            }

            if (formattedFile.endsWith(".disabled")) {
                LOGGER.info("File {} is disabled! Skipping...", formattedFile);
                return null;
            }

            if (formattedFile.endsWith(".bak")) {
                LOGGER.info("File {} is backup file, unnecessary on client! Skipping...", formattedFile);
                return null;
            }
        }

        String type;

        if (FileInspection.isMod(file)) {
            type = "mod";
            if (serverConfig.autoExcludeServerSideMods && Objects.equals(FileInspection.getModEnvironment(file), LoaderManagerService.EnvironmentType.SERVER)) {
                LOGGER.info("File {} is server mod! Skipping...", formattedFile);
                return null;
            }
            // Exclude AutoModpack itself
            var modId = FileInspection.getModID(file);
            if ((MOD_ID + "_bootstrap").equals(modId) || (MOD_ID + "-bootstrap").equals(modId) || (MOD_ID + "_mod").equals(modId) || MOD_ID.equals(modId)) {
                return null;
            }
        } else if (formattedFile.contains("/config/")) {
            type = "config";
        } else if (formattedFile.contains("/shaderpacks/")) {
            type = "shader";
        } else if (formattedFile.contains("/resourcepacks/")) {
            type = "resourcepack";
        } else if (formattedFile.endsWith("/options.txt")) {
            type = "mc_options";
        } else {
            type = "other";
        }

        String sha1 = metadataCache != null ? metadataCache.getOrComputeHash(file) : SmartFileUtils.getHash(file);

        // For CF API
        String murmur = null;
        if (type.equals("mod") || type.equals("shader") || type.equals("resourcepack")) {
            murmur = sha1MurmurMapPreviousContent.get(sha1); // Get from cache
            if (murmur == null) {
                murmur = SmartFileUtils.getCurseforgeMurmurHash(file);
            }
        }

        boolean isEditable = false;
        if (EDITABLE_CARDS.hasMatch(formattedFile)) {
            isEditable = true;
            LOGGER.info("File {} is editable!", formattedFile);
        }

        boolean forcedToCopy = false;
        if (FORCE_COPY_FILES_TO_STANDARD_LOCATION.hasMatch(formattedFile)) {
            forcedToCopy = true;
            LOGGER.info("File {} is forced to copy to standard location!", formattedFile);
        }

        return new Jsons.ModpackContentFields.ModpackContentItem(formattedFile, size, type, isEditable, forcedToCopy, sha1, murmur);
    }
}