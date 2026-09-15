package pl.skidam.automodpack_loader_core_neoforge.loader;

import static pl.skidam.automodpack_core.Constants.*;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core_neoforge.EarlyModLocator;

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
		// getVersionInfo() is only reachable through the loader instance once it is current (see
		// EarlyModLocator) - fall back to the value captured off the launch context during discovery;
		// by the time preload is false, getVersionInfo() is always populated.
		if (preload && EarlyModLocator.EARLY_NEOFORGE_VERSION != null) return EarlyModLocator.EARLY_NEOFORGE_VERSION;
		return FMLLoader.getCurrent().getVersionInfo().neoForgeVersion();
	}

	@Override
	public EnvironmentType getEnvironmentType() {
		// At mod-construction time the loader-native dist is authoritative: the launch-context
		// distribution exists only for preload, where FMLLoader's dist isn't populated yet (see
		// EarlyModLocator). Trusting the capture past preload let a stale or misparsed
		// launchTarget report CLIENT on a real dedicated server and crash mod construction.
		if (preload && EarlyModLocator.EARLY_IS_CLIENT != null) {
			return EarlyModLocator.EARLY_IS_CLIENT ? EnvironmentType.CLIENT : EnvironmentType.SERVER;
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
				if (EarlyModLocator.EARLY_MC_VERSION != null) return EarlyModLocator.EARLY_MC_VERSION;
				return FMLLoader.getCurrent().getVersionInfo().mcVersion();
			}

			return null;
		}

		ModInfo modInfo = FMLLoader.getCurrent().getLoadingModList().getMods().stream().filter(mod -> mod.getModId().equals(modId)).findFirst().orElse(null);

		if (modInfo == null) return null;

		return modInfo.getVersion().toString();
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLLoader.getCurrent().isProduction();
	}
}
