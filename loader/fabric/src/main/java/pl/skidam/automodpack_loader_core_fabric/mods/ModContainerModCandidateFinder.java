package pl.skidam.automodpack_loader_core_fabric.mods;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.impl.discovery.ClasspathModCandidateFinder;

import java.util.List;

public class ModContainerModCandidateFinder extends ClasspathModCandidateFinder {
    private final List<ModContainer> containers;

    public ModContainerModCandidateFinder(List<ModContainer> containers) {
        this.containers = containers;
    }

    @Override
    public void findCandidates(ModCandidateConsumer out) {
        containers.forEach((ModContainer container) -> {
            // Nested are added in ModResolver#resolve
            if (container.getOrigin().getKind().equals(ModOrigin.Kind.PATH)) {
                out.accept(container.getOrigin().getPaths(), false);
            }
        });
    }
}