package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class FullServerPack {
    public final ModpackExecutor executor;

    public final Map<String, FullServerPackContent> fullpacks = Collections.synchronizedMap(new HashMap<>());

    public FullServerPack(ModpackExecutor executor) {
        this.executor = executor;
    }



    private FullServerPackContent init() {
        LOGGER.info("init() von FullServerPack wurde aufgerufen");
        if (isGenerating()) {
            LOGGER.error("Called generate() twice!");
            return null;
        }

        try {
            if (!Files.exists(hostContentModpackDir)) {
                Files.createDirectories(hostContentModpackDir);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create modpack directory", e);
            e.printStackTrace();
            return null;
        }

        return new FullServerPackContent(serverConfig.modpackName, hostContentModpackDir, executor.getExecutor());
    }

    public boolean generateNew(FullServerPackContent content) {
        if (content == null) return false;
        boolean generated = content.create();
        fullpacks.put(content.getModpackName(), content);
        return generated;
    }

    public boolean generateNew() {
        FullServerPackContent content = init();
        if (content == null) return false;
        boolean generated = content.create();
        fullpacks.put(content.getModpackName(), content);
        return generated;
    }

    public boolean isGenerating() {
        return executor.isRunning();
        /* Old executer

        int activeCount = CREATION_EXECUTOR.getActiveCount();
        int queueSize = CREATION_EXECUTOR.getQueue().size();
        return activeCount > 0 || queueSize > 0;

        */
    }

    public void shutdownExecutor() {
        executor.stop();

        /* Old executer
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
        */

    }
}