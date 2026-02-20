package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.FileTreeScanner;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/**
 * Group-based Modpack Executor.
 * Orchestrates the scanning and generation of modpack content based on server groups.
 */
public class ModpackExecutor {

    private final ThreadPoolExecutor CREATION_EXECUTOR = (ThreadPoolExecutor) Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() * 2),
            new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build()
    );

    public final Map<String, ModpackContent> modpacks = new ConcurrentHashMap<>();

    // Use ConcurrentHashMap for thread safety across scanners
    private final Map<String, String> sha1MurmurMapCache = new ConcurrentHashMap<>();
    private final Map<String, GroupContentScanner> groupScanners = new ConcurrentHashMap<>();

    // Precise state tracking
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);

    public ModpackExecutor() {
        // Initialization
    }

    /**
     * Called by ServerMessageHandler to refresh specific files.
     * The FileMetadataCache will automatically skip unmodified files, making this very performant.
     */
    // TODO consider actually refreshing only the files client asked for instead of full regeneration since it may add new files which we didnt want?
    public CompletableFuture<Void> refreshFiles(Set<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.info("Requested refresh for {} files. Triggering modpack regeneration...", hashes.size());

        // Run asynchronously so we don't block the Netty worker thread
        return CompletableFuture.runAsync(this::generateNew, CREATION_EXECUTOR).exceptionally(ex -> {
            LOGGER.error("Failed to refresh files during regeneration", ex);
            return null;
        });
    }

    // TODO use Semaphores instead of atomic integers so we can stale requests and imediatly say that they are done whenever whichever thread finishes first
    public boolean generateNew() {
        if (!isGenerating.compareAndSet(false, true)) {
            LOGGER.warn("Called generateNew() while already generating!");
            return false;
        }

        try {
            if (!Files.exists(hostModpackDir)) {
                Files.createDirectories(hostModpackDir);
            }

            try (var cache = FileMetadataCache.open(hashCacheDBFile)) {

                Jsons.ModpackContent content = new Jsons.ModpackContent();
                content.modpackName = serverConfig.modpackName;

                Map<String, Path> globalPathMap = new ConcurrentHashMap<>();
                groupScanners.clear(); // Clear existing scanners before regenerating

                // Get defined groups from server configuration
                Map<String, Jsons.GroupDeclaration> declaredGroups = serverConfig.groups;
                LOGGER.info("Groups to include: {}", declaredGroups.keySet());

                // Execute scanners dynamically based on the group definitions mapped in config
                for (Map.Entry<String, Jsons.GroupDeclaration> groupEntry : declaredGroups.entrySet()) {
                    String groupId = groupEntry.getKey();
                    Jsons.GroupDeclaration decl = groupEntry.getValue();

                    Path groupDir = hostModpackDir.resolve(groupId);
                    if (!Files.exists(groupDir)) {
                        Files.createDirectories(groupDir);
                        Files.createDirectory(groupDir.resolve("mods"));
                        Files.createDirectory(groupDir.resolve("config"));
                        Files.createDirectory(groupDir.resolve("shaderpacks"));
                        Files.createDirectory(groupDir.resolve("resourcepacks"));
                    }

                    // Create specific scanners for this exact group
                    FileTreeScanner syncedFilesScanner = new FileTreeScanner(new HashSet<>(decl.syncedFiles), Set.of(SmartFileUtils.CWD));
                    FileTreeScanner editableFilesScanner = new FileTreeScanner(new HashSet<>(decl.allowEditsInFiles), Set.of(SmartFileUtils.CWD, groupDir));
                    FileTreeScanner forceCopyScanner = new FileTreeScanner(new HashSet<>(decl.forceCopyFilesToStandardLocation), Set.of(SmartFileUtils.CWD, groupDir));

                    GroupContentScanner scanner = new GroupContentScanner(
                            groupId, groupDir, decl, syncedFilesScanner, editableFilesScanner, forceCopyScanner,
                            CREATION_EXECUTOR, sha1MurmurMapCache, cache
                    );

                    groupScanners.put(groupId, scanner);
                    scanner.scanAndGenerate();

                    content.groups.put(groupId, scanner.getGroupFields());
                    globalPathMap.putAll(scanner.getFileHashToPathMap());
                }

                ModpackContent finalContent = new ModpackContent(content, globalPathMap);
                this.modpacks.put(content.modpackName, finalContent);

                ConfigTools.saveModpackContent(hostModpackContentFile, content);
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Error generating modpack", e);
            return false;
        } finally {
            isGenerating.set(false);
        }
    }

    public boolean loadLast() {
        if (!isGenerating.compareAndSet(false, true)) {
            LOGGER.warn("Called loadLast() while generating!");
            return false;
        }

        try {
            if (!Files.exists(hostModpackContentFile)) {
                return false;
            }

            Jsons.ModpackContent jsonContent = ConfigTools.loadModpackContent(hostModpackContentFile);
            if (jsonContent == null || jsonContent.groups == null) {
                return false;
            }

            Map<String, Path> globalPathMap = new ConcurrentHashMap<>();

            for (Map.Entry<String, Jsons.ModpackGroupFields> entry : jsonContent.groups.entrySet()) {
                String groupId = entry.getKey();
                Jsons.ModpackGroupFields groupFields = entry.getValue();
                Path groupDir = hostModpackDir.resolve(groupId);

                if (groupFields.files == null) continue;

                for (Jsons.ModpackContentItem item : groupFields.files) {
                    if (item.sha1 != null && !item.sha1.isEmpty()) {
                        Path groupPath = SmartFileUtils.getPath(groupDir, item.file);
                        Path serverPath = SmartFileUtils.getPathFromCWD(item.file);

                        if (Files.exists(groupPath)) {
                            globalPathMap.put(item.sha1, groupPath);
                        } else if (Files.exists(serverPath)) {
                            globalPathMap.put(item.sha1, serverPath);
                        } else {
                            LOGGER.warn("File listed in modpack content but missing from disk: {}", item.file);
                        }
                    }
                }
            }

            ModpackContent content = new ModpackContent(jsonContent, globalPathMap);
            modpacks.put(jsonContent.modpackName, content);

            LOGGER.info("Modpack '{}' loaded successfully.", jsonContent.modpackName);
            return true;

        } catch (Exception e) {
            LOGGER.error("Error loading last modpack", e);
            return false;
        } finally {
            isGenerating.set(false);
        }
    }

    public boolean isGenerating() {
        return isGenerating.get();
    }

    public ThreadPoolExecutor getExecutor() {
        return CREATION_EXECUTOR;
    }

    public void stop() {
        CREATION_EXECUTOR.shutdown();
        try {
            if (!CREATION_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                CREATION_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            CREATION_EXECUTOR.shutdownNow();
        }
    }
}