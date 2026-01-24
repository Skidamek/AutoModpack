package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

import static pl.skidam.automodpack_core.Constants.*;

public class ModpackExecutor {
    private final ThreadPoolExecutor CREATION_EXECUTOR = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() * 2), new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build());
    public final Map<String, ModpackContent> modpacks = new ConcurrentHashMap<>();
    private final FileMetadataCache fileMetadataCache;

    public ModpackExecutor() {
        this.fileMetadataCache = new FileMetadataCache(hashCacheDBFile);
    }

    private ModpackContent init() {
        if (isGenerating()) {
            LOGGER.error("Called generate() twice!");
            return null;
        }

        try {
            if (!Files.exists(hostContentModpackDir)) {
                Files.createDirectories(hostContentModpackDir);
                Files.createDirectory(hostContentModpackDir.resolve("mods"));
                Files.createDirectory(hostContentModpackDir.resolve("config"));
                Files.createDirectory(hostContentModpackDir.resolve("shaderpacks"));
                Files.createDirectory(hostContentModpackDir.resolve("resourcepacks"));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create modpack content directory!", e);
            return null;
        }

        return new ModpackContent(serverConfig.modpackName, SmartFileUtils.CWD, hostContentModpackDir, serverConfig.syncedFiles, serverConfig.allowEditsInFiles, serverConfig.forceCopyFilesToStandardLocation, CREATION_EXECUTOR, fileMetadataCache);
    }

    public boolean generateNew(ModpackContent content) {
        if (content == null) return false;
        boolean generated = content.create();
        modpacks.put(content.getModpackName(), content);
        return generated;
    }

    public boolean generateNew() {
        ModpackContent content = init();
        if (content == null) return false;
        boolean generated = content.create();
        modpacks.put(content.getModpackName(), content);
        return generated;
    }

    public boolean loadLast() {
        ModpackContent content = init();
        if (content == null) return false;
        boolean generated = content.loadPreviousContent();
        modpacks.put(content.getModpackName(), content);
        return generated;
    }

    public boolean isGenerating() {
        int activeCount = CREATION_EXECUTOR.getActiveCount();
        int queueSize = CREATION_EXECUTOR.getQueue().size();
        return activeCount > 0 || queueSize > 0;
    }

    public ThreadPoolExecutor getExecutor() {
        return CREATION_EXECUTOR;
    }

    public void stop() {
        CREATION_EXECUTOR.shutdown();
        fileMetadataCache.close();
    }
}