package pl.skidam.automodpack_loader_core_neoforge.loader;

import static pl.skidam.automodpack_core.Constants.*;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core_neoforge.EarlyServiceBootstrapper;

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
			loadingModList = FMLLoader.getCurrent().getLoadingModList();
		} catch (IllegalStateException e) {
			return false;
		}
		return loadingModList.getModFileById(modId) != null;
	}

	@Override
	public String getLoaderVersion() {
		// getVersionInfo() is still null when Preload runs from GraphicsBootstrapper (see
		// EarlyServiceBootstrapper) - fall back to the value it captured straight off the command
		// line for that window; by the time preload is false, getVersionInfo() is always populated.
		if (preload && EarlyServiceBootstrapper.EARLY_NEOFORGE_VERSION != null) { return EarlyServiceBootstrapper.EARLY_NEOFORGE_VERSION; }
		return FMLLoader.getCurrent().getVersionInfo().neoForgeVersion();
	}

	@Override
	public EnvironmentType getEnvironmentType() {
		// FMLLoader.getCurrent().getDist() is unreliable during preload (see
		// EarlyServiceBootstrapper) - prefer the dist captured from --launchTarget on the command
		// line when it's available.
		if (EarlyServiceBootstrapper.EARLY_IS_CLIENT != null) {
			return EarlyServiceBootstrapper.EARLY_IS_CLIENT ? EnvironmentType.CLIENT : EnvironmentType.SERVER;
		}
		if (FMLLoader.getCurrent().getDist() == Dist.CLIENT) {
			return EnvironmentType.CLIENT;
		} else {
			return EnvironmentType.SERVER;
		}
	}

	@Override
	public String getModVersion(String modId) {
		if (preload) {
			if (modId.equals("minecraft")) {
				if (EarlyServiceBootstrapper.EARLY_MC_VERSION != null) { return EarlyServiceBootstrapper.EARLY_MC_VERSION; }
				return FMLLoader.getCurrent().getVersionInfo().mcVersion();
			}

			return null;
		}

		ModInfo modInfo = FMLLoader.getCurrent().getLoadingModList().getMods().stream().filter(mod -> mod.getModId().equals(modId)).findFirst().orElse(null);

		if (modInfo == null) { return null; }

		return modInfo.getVersion().toString();
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLLoader.getCurrent().isProduction();
	}
}
