package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.FileTreeScanner;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/**
 * Scans a single group directory based on its GroupDeclaration
 * and generates ModpackContentItem entries.
 */
public class GroupContentScanner {

    private final String groupId;
    private final Path groupDirectory;
    private final FileTreeScanner syncedFilesScanner;
    private final FileTreeScanner editableFilesScanner;
    private final FileTreeScanner forceCopyScanner;
    private final ThreadPoolExecutor executorService;
    private final Map<String, String> sha1MurmurMapCache;
    private final FileMetadataCache cache;

    private final Jsons.ModpackGroupFields groupFields;
    private final Map<String, Path> fileHashToPathMap = new ConcurrentHashMap<>();

    // TODO consider getting murmur cache from actual db cache instead of the older modpack content
    public GroupContentScanner(String groupId, Path groupDirectory, Jsons.GroupDeclaration groupDeclaration,
                               FileTreeScanner syncedFilesScanner, FileTreeScanner editableFilesScanner,
                               FileTreeScanner forceCopyScanner, ThreadPoolExecutor executorService,
                               Map<String, String> sha1MurmurMapCache, FileMetadataCache cache) {
        this.groupId = groupId;
        this.groupDirectory = groupDirectory;
        this.syncedFilesScanner = syncedFilesScanner;
        this.editableFilesScanner = editableFilesScanner;
        this.forceCopyScanner = forceCopyScanner;
        this.executorService = executorService;
        this.sha1MurmurMapCache = sha1MurmurMapCache;
        this.cache = cache;

        this.groupFields = new Jsons.ModpackGroupFields(groupDeclaration);
    }

    public void scanAndGenerate() {
        Map<String, Path> finalFilesMap = new HashMap<>();

        // Process syncedFiles (Lower Priority)
        if (syncedFilesScanner != null) {
            syncedFilesScanner.scan();
            for (Map.Entry<String, Path> entry : syncedFilesScanner.getMatchedPaths().entrySet()) {
                String relativePath = SmartFileUtils.formatPath(entry.getValue(), SmartFileUtils.CWD);
                finalFilesMap.put(relativePath, entry.getValue());
            }
        }

        // Process host-modpack/<group>/ directory (Higher Priority)
        if (Files.exists(groupDirectory)) {
            try (Stream<Path> paths = Files.walk(groupDirectory)) {
                paths.filter(Files::isRegularFile).forEach(file -> {
                    String relativePath = SmartFileUtils.formatPath(file, groupDirectory);
                    finalFilesMap.put(relativePath, file);
                });
            } catch (IOException e) {
                LOGGER.error("Failed to walk group directory: " + groupDirectory, e);
            }
        }

        // Execute the secondary attribute scanners so their internal maps are populated!
        if (editableFilesScanner != null) {
            editableFilesScanner.scan();
        }
        if (forceCopyScanner != null) {
            forceCopyScanner.scan();
        }

        // Process hashes in parallel
        List<CompletableFuture<Jsons.ModpackContentItem>> futures = new ArrayList<>();

        for (Map.Entry<String, Path> entry : finalFilesMap.entrySet()) {
            String relativePath = entry.getKey();
            Path absoluteDiskPath = entry.getValue();

            futures.add(CompletableFuture.supplyAsync(() -> processFile(absoluteDiskPath, relativePath), executorService));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Collect successful items
        Set<Jsons.ModpackContentItem> groupItems = new HashSet<>();
        for (CompletableFuture<Jsons.ModpackContentItem> future : futures) {
            try {
                Jsons.ModpackContentItem item = future.get();
                if (item != null) {
                    groupItems.add(item);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to retrieve processed file item", e);
            }
        }

        this.groupFields.files = groupItems;
    }

    private Jsons.ModpackContentItem processFile(Path absoluteDiskPath, String formattedFile) {
        try {
            long size = SmartFileUtils.size(absoluteDiskPath);
            String type = determineFileType(absoluteDiskPath, formattedFile);

            if (type == null) {
                return null;
            }

            String sha1 = cache != null ? cache.getHashOrNull(absoluteDiskPath) : HashUtils.getHash(absoluteDiskPath);

            String murmur = null;
            if ("mod".equals(type) || "shader".equals(type) || "resourcepack".equals(type)) {
                murmur = sha1MurmurMapCache.get(sha1);
                if (murmur == null) {
                    murmur = HashUtils.getCurseforgeMurmurHash(absoluteDiskPath);
                    if (murmur != null) {
                        sha1MurmurMapCache.put(sha1, murmur);
                    }
                }
                if ("mod".equals(type)) {
                    if (serverConfig.autoExcludeServerSideMods && Objects.equals(FileInspection.getModEnvironment(absoluteDiskPath), LoaderManagerService.EnvironmentType.SERVER)) {
                        LOGGER.info("File {} is server mod! Skipping...", formattedFile);
                        return null;
                    }
                    // Exclude AutoModpack itself
                    var modId = FileInspection.getModID(absoluteDiskPath);
                    if ((MOD_ID + "_bootstrap").equals(modId) || (MOD_ID + "-bootstrap").equals(modId) || (MOD_ID + "_mod").equals(modId) || MOD_ID.equals(modId)) {
                        return null;
                    }
                }
            }

            if (serverConfig.autoExcludeUnnecessaryFiles) {
                if (size == 0) {
                    LOGGER.info("Skipping file {} because it is empty", formattedFile);
                    return null;
                }

                if (absoluteDiskPath.getFileName().toString().startsWith(".")) {
                    LOGGER.info("Skipping file {} is hidden", formattedFile);
                    return null;
                }

                // check if any parent dir name starts with a dot
                if (StreamSupport.stream(absoluteDiskPath.getParent().spliterator(), false).anyMatch(p -> p.toString().startsWith("."))) {
                    LOGGER.info("Skipping file {} it is inside a hidden directory", formattedFile);
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

                if (formattedFile.equals("Zone.Identifier")) {
                    LOGGER.info("File {} is a Windows Zone.Identifier file, useless for client! Skipping...", formattedFile);
                    return null;
                }
            }

            boolean isEditable = editableFilesScanner != null && editableFilesScanner.hasMatch(formattedFile);
            boolean forcedToCopy = forceCopyScanner != null && forceCopyScanner.hasMatch(formattedFile);

            fileHashToPathMap.put(sha1, absoluteDiskPath);

            return new Jsons.ModpackContentItem(formattedFile, size, type, isEditable, forcedToCopy, sha1, murmur);

        } catch (Exception e) {
            LOGGER.error("Failed to process file: " + absoluteDiskPath, e);
            return null;
        }
    }

    private String determineFileType(Path file, String formattedFile) {
        if (FileInspection.isMod(file)) {
            if (serverConfig != null && serverConfig.autoExcludeServerSideMods) {
                var envType = FileInspection.getModEnvironment(file);
                if (LoaderManagerService.EnvironmentType.SERVER.equals(envType)) {
                    LOGGER.debug("Skipping server-side mod {} in group {}", formattedFile, groupId);
                    return null;
                }
            }
            return "mod";
        } else if (formattedFile.contains("/config/")) {
            return "config";
        } else if (formattedFile.contains("/shaderpacks/")) {
            return "shader";
        } else if (formattedFile.contains("/resourcepacks/")) {
            return "resourcepack";
        } else if (formattedFile.endsWith("/options.txt")) {
            return "mc_options";
        } else {
            return "other";
        }
    }

    public String getGroupId() {
        return groupId;
    }

    public Path getGroupDirectory() {
        return groupDirectory;
    }

    public Jsons.ModpackGroupFields getGroupFields() {
        return groupFields;
    }

    public Map<String, Path> getFileHashToPathMap() {
        return fileHashToPathMap;
    }
}