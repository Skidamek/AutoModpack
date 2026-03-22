package pl.skidam.automodpack_core.protocol;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackContent;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.Constants.hashCacheDBFile;
import static pl.skidam.automodpack_core.Constants.hostModpackContentFile;
import static pl.skidam.automodpack_core.Constants.hostServer;
import static pl.skidam.automodpack_core.Constants.modpackExecutor;

public final class HostModpackOperations {
    private HostModpackOperations() {
    }

    public static Optional<Path> resolvePath(String sha1) {
        if (sha1 == null || sha1.isBlank()) {
            return Optional.of(hostModpackContentFile);
        }
        return hostServer.getPath(sha1);
    }

    public static void refreshModpackFiles(byte[][] fileHashesList) throws IOException {
        Set<String> hashes = new HashSet<>();
        for (byte[] hash : fileHashesList) {
            hashes.add(new String(hash));
        }

        List<CompletableFuture<Void>> creationFutures = new ArrayList<>();
        Set<ModpackContent> modpacks = new HashSet<>();

        try (var cache = FileMetadataCache.open(hashCacheDBFile)) {
            for (String hash : hashes) {
                final Optional<Path> optionalPath = resolvePath(hash);
                if (optionalPath.isEmpty()) {
                    continue;
                }

                Path path = optionalPath.get();
                ModpackContent modpack = null;

                for (var content : modpackExecutor.modpacks.values()) {
                    if (!content.pathsMap.getMap().containsKey(hash)) {
                        continue;
                    }
                    modpack = content;
                    break;
                }

                if (modpack == null) {
                    continue;
                }

                modpacks.add(modpack);
                creationFutures.add(modpack.replaceAsync(path, cache));
            }
        }

        creationFutures.forEach(CompletableFuture::join);
        modpacks.forEach(modpackContent -> {
            var optionalPreviousModpackContent = modpackContent.getPreviousContent();
            if (optionalPreviousModpackContent.isEmpty()) {
                return;
            }
            Jsons.ModpackContentFields previousModpackContent = optionalPreviousModpackContent.get();
            modpackContent.saveModpackContent(previousModpackContent.nonModpackFilesToDelete);
        });
    }
}
