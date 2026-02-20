package pl.skidam.automodpack_core.modpack;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import pl.skidam.automodpack_core.config.Jsons;

public class ModpackContent {

    private final Jsons.ModpackContent content;

    // A thread-safe map for caching file paths in memory for Netty server requests
    private final Map<String, Path> pathsMap = new ConcurrentHashMap<>();

    public ModpackContent(Jsons.ModpackContent content, Map<String, Path> globalPathMap) {
        this.content = content;
        if (globalPathMap != null) {
            this.pathsMap.putAll(globalPathMap);
        }
    }

    public String getModpackName() {
        return content.modpackName;
    }

    public Jsons.ModpackContent getContent() {
        return content;
    }

    public boolean isEmpty() {
        return pathsMap.isEmpty();
    }

    public Path getPath(String hash) {
        return pathsMap.get(hash);
    }
}