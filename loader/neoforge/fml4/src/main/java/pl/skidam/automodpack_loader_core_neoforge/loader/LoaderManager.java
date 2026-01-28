package pl.skidam.automodpack_loader_core_neoforge.loader;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import pl.skidam.automodpack_core.loader.LoaderManagerService;

import static pl.skidam.automodpack_core.Constants.*;

@SuppressWarnings("unused")
public class LoaderManager implements LoaderManagerService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.NEOFORGE;
    }

    @Override
    public boolean isModLoaded(String modId) {
        LoadingModList loadingModList;
        try {
            loadingModList= FMLLoader.getLoadingModList();
        } catch (IllegalStateException e) {
            return false;
        }
        return loadingModList.getModFileById(modId) != null;
    }

    @Override
    public String getLoaderVersion() {
        return FMLLoader.versionInfo().neoForgeVersion();
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        if (FMLLoader.getDist() == Dist.CLIENT) {
            return EnvironmentType.CLIENT;
        } else {
            return EnvironmentType.SERVER;
        }
    }

    @Override
    public String getModVersion(String modId) {
        if (preload) {
            if (modId.equals("minecraft")) {
                return FMLLoader.versionInfo().mcVersion();
            }

            return null;
        }

        ModInfo modInfo = FMLLoader.getLoadingModList().getMods().stream().filter(mod -> mod.getModId().equals(modId)).findFirst().orElse(null);

        if (modInfo == null) {
            return null;
        }

        return modInfo.getVersion().toString();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }
}