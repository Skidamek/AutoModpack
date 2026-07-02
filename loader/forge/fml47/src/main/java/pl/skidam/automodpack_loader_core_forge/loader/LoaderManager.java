package pl.skidam.automodpack_loader_core_forge.loader;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core_forge.AutoModpackTransformationService;

import static pl.skidam.automodpack_core.Constants.*;

@SuppressWarnings("unused")
public class LoaderManager implements LoaderManagerService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.FORGE;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }

    @Override
    public String getLoaderVersion() {
        // versionInfo() is still null when Preload runs from onLoad() (see
        // AutoModpackTransformationService) - fall back to the value it captured from the JVM's
        // own process arguments for that window; once preload is false, versionInfo() is populated.
        if (preload && AutoModpackTransformationService.EARLY_FORGE_VERSION != null) {
            return AutoModpackTransformationService.EARLY_FORGE_VERSION;
        }
        return FMLLoader.versionInfo().forgeVersion();
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        // FMLLoader.getDist() is unreliable during preload (see AutoModpackTransformationService) -
        // prefer the dist captured from --launchTarget on the command line when it's available.
        if (AutoModpackTransformationService.EARLY_IS_CLIENT != null) {
            return AutoModpackTransformationService.EARLY_IS_CLIENT ? EnvironmentType.CLIENT : EnvironmentType.SERVER;
        }
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
                if (AutoModpackTransformationService.EARLY_MC_VERSION != null) {
                    return AutoModpackTransformationService.EARLY_MC_VERSION;
                }
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