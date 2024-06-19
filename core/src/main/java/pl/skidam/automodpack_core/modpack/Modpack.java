package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.utils.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Modpack {
    public final ThreadPoolExecutor CREATION_EXECUTOR = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() * 2), new CustomThreadFactoryBuilder().setNameFormat("AutoModpackCreation-%d").build());
    public final Map<String, ModpackContent> modpacks = Collections.synchronizedMap(new HashMap<>());

    public boolean generateNew() {
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

        var content = new ModpackContent(serverConfig.modpackName, serverConfig.syncedFiles, serverConfig.allowEditsInFiles, CREATION_EXECUTOR);
        boolean generated = content.create(Path.of(System.getProperty("user.dir")), hostContentModpackDir);
        modpacks.put(content.getModpackName(), content);
        return generated;
    }

    public boolean generate(String modpackName) {
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

        var content = modpacks.get(modpackName);
        if (content == null) {
            LOGGER.error("Modpack with name " + modpackName + " does not exist!");
            return false;
        }

        boolean generated = content.create(Path.of(System.getProperty("user.dir")), hostContentModpackDir);
        modpacks.put(content.getModpackName(), content);
        return generated;
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