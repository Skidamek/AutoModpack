package pl.skidam.automodpack_loader_core_fabric.mods;

import net.fabricmc.loader.impl.discovery.DirectoryModCandidateFinder;

import java.nio.file.Path;
import java.util.List;

public class PathModCandidateFinder extends DirectoryModCandidateFinder {
    private final Path path;

    public PathModCandidateFinder(Path path, boolean requiresRemap) {
        super(path.getParent(), requiresRemap);
        this.path = path;
    }

    @Override
    public void findCandidates(ModCandidateConsumer out) {
        super.findCandidates((final List<Path> pathList, final boolean requiresRemap) -> {
            // pathList is a list with one path of the mod file
            if (!pathList.contains(this.path)) {
                return;
            }

            out.accept(path, requiresRemap);
        });
    }
}