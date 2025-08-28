package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.utils.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ModpackExecutor {
    private final ThreadPoolExecutor CREATION_EXECUTOR = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() * 2), new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build());
    public final Map<String, ModpackContent> modpacks = Collections.synchronizedMap(new HashMap<>());

    private ModpackContent init(String groupId) {
        if (isGenerating()) {
            LOGGER.error("Called generate() twice!");
            return null;
        }

        Path modpackHostPath = hostModpackDir.resolve(groupId);

        try {
            if (!Files.exists(modpackHostPath)) {
                Files.createDirectories(modpackHostPath);
                Files.createDirectory(modpackHostPath.resolve("mods"));
                Files.createDirectory(modpackHostPath.resolve("config"));
                Files.createDirectory(modpackHostPath.resolve("shaderpacks"));
                Files.createDirectory(modpackHostPath.resolve("resourcepacks"));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        var groupDecl = serverConfig.groups.get(groupId);

        Path cwd = Path.of(System.getProperty("user.dir"));
        return new ModpackContent(groupDecl.groupName, cwd, modpackHostPath, groupDecl.syncedFiles, groupDecl.allowEditsInFiles, groupDecl.forceCopyFilesToStandardLocation, groupDecl.autoExcludeUnnecessaryFiles, groupDecl.autoExcludeServerSideMods, CREATION_EXECUTOR);
    }

    public boolean generateNew(ModpackContent content) {
        if (content == null) return false;
        boolean generated = content.create();
        modpacks.put(content.getModpackName(), content);
        return generated;
    }

    public boolean generateNew(String groupId) {
        ModpackContent content = init(groupId);
        if (content == null) return false;
        boolean generated = content.create();
        modpacks.put(content.getModpackName(), content);
        return generated;
    }

    public boolean loadLast(String groupId) {
        ModpackContent content = init(groupId);
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

    public boolean isRunning() {
        return !CREATION_EXECUTOR.isShutdown() && !CREATION_EXECUTOR.isTerminated();
    }

    public ThreadPoolExecutor getExecutor() {
        return CREATION_EXECUTOR;
    }

    public void stop() {
        CREATION_EXECUTOR.shutdown();
    }
}