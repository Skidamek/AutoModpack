package pl.skidam.automodpack_loader_core_fabric.loader;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.ClientCacheUtils;
import pl.skidam.automodpack_core.utils.FileInspection;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

@SuppressWarnings("unused")
public class LoaderManager implements LoaderManagerService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.FABRIC;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    private Collection<FileInspection.Mod> modList = new ArrayList<>();
    private int lastLoadingModListSize = -1;

    @Override
    public Collection<FileInspection.Mod> getModList() {

        Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();

        if (!modList.isEmpty() && lastLoadingModListSize == mods.size()) {
            return modList;
        }

        lastLoadingModListSize = mods.size();
        Collection<FileInspection.Mod> modList = new ArrayList<>();

        for (var info : mods) {
            try {
                String modID = info.getMetadata().getId();
                Path path = getModPath(modID);
                if (path == null || path.toString().isEmpty()) // If we cant get the path, we skip the mod, its probably JiJed, we dont need it in the list
                    continue;

                if (!Files.exists(path))
                    continue;

                String hash = ClientCacheUtils.computeHashIfNeeded(path);
                if (hash == null)
                    continue;

                Set<String> providesIDs = new HashSet<>(info.getMetadata().getProvides());
                List<String> dependencies = info.getMetadata().getDependencies().stream().filter(d -> d.getKind().equals(ModDependency.Kind.DEPENDS)).map(ModDependency::getModId).toList();

                FileInspection.Mod mod = new FileInspection.Mod(
                        modID,
                        hash,
                        providesIDs,
                        info.getMetadata().getVersion().getFriendlyString(),
                        path,
                        getModEnvironment(modID),
                        dependencies);

                modList.add(mod);
            } catch (Exception ignored) {}
        }

        return this.modList = modList;
    }

    @Override
    public String getLoaderVersion() {
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("fabricloader");
        return modContainer.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    private Path getModPath(String modId) {
        if (!isModLoaded(modId)) return null;

        try {
            for (ModContainer modContainer : FabricLoader.getInstance().getAllMods()) {
                if (modContainer.getMetadata().getId().equals(modId)) {
                    FileSystem fileSys = modContainer.getRootPaths().get(0).getFileSystem();
                    return Path.of(fileSys.toString());
                }
            }
        } catch (Exception ignored) {}

        LOGGER.error("Could not find jar file for {}", modId);
        return null;
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            return EnvironmentType.CLIENT;
        } else {
            return EnvironmentType.SERVER;
        }
    }

    @Override
    public String getModVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).isPresent() ? FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getVersion().getFriendlyString() : null;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    private EnvironmentType getModEnvironment(String modId) {
        var container = FabricLoader.getInstance().getModContainer(modId);
        if (container.isEmpty()) {
            return EnvironmentType.UNIVERSAL;
        }
        ModEnvironment env = container.get().getMetadata().getEnvironment();
        if (env == ModEnvironment.CLIENT) {
            return EnvironmentType.CLIENT;
        } else if (env == ModEnvironment.SERVER) {
            return EnvironmentType.SERVER;
        } else {
            return EnvironmentType.UNIVERSAL;
        }
    }
}
