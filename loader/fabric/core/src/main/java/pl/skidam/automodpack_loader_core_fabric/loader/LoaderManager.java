package pl.skidam.automodpack_loader_core_fabric.loader;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import pl.skidam.automodpack_core.loader.LoaderManagerService;

import java.util.*;

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

    @Override
    public String getLoaderVersion() {
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("fabricloader");
        return modContainer.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse(null);
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
