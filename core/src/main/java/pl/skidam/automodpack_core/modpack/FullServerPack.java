package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class FullServerPack {
    public final ThreadPoolExecutor CREATION_EXECUTOR = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() * 2), new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build());

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

        return new FullServerPackContent(serverConfig.modpackName, hostContentModpackDir, CREATION_EXECUTOR);
    }

    public boolean generateNew(FullServerPackContent content) {
        if (content == null) return false;
        return content.create();
    }

    public boolean generateNew() {
        FullServerPackContent content = init();
        if (content == null) return false;
        return content.create();
    }

    public boolean isGenerating() {
        int activeCount = CREATION_EXECUTOR.getActiveCount();
        int queueSize = CREATION_EXECUTOR.getQueue().size();
        return activeCount > 0 || queueSize > 0;
    }

    public void shutdownExecutor() {
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
}