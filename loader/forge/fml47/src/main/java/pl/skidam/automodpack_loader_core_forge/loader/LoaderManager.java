package pl.skidam.automodpack_loader_core_forge.loader;

import static pl.skidam.automodpack_core.Constants.preload;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.VersionInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core_forge.AutoModpackTransformationService;

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
		// AutoModpackTransformationService) - use the launch args captured there for that window;
		// once preload is false, versionInfo() is populated.
		if (preload && AutoModpackTransformationService.EARLY_FORGE_VERSION != null) return AutoModpackTransformationService.EARLY_FORGE_VERSION;
		VersionInfo versionInfo = FMLLoader.versionInfo();
		if (versionInfo != null) return versionInfo.forgeVersion();
		throw new IllegalStateException("Forge version is not available yet");
	}

	@Override
	public EnvironmentType getEnvironmentType() {
		// At mod-construction time the loader-native dist is authoritative: the --launchTarget
		// heuristic exists only for preload, where FMLLoader's dist isn't populated yet (see
		// AutoModpackTransformationService). Trusting the heuristic past preload let a stale or
		// misparsed launchTarget report CLIENT on a real dedicated server and crash mod construction.
		if (preload && AutoModpackTransformationService.EARLY_IS_CLIENT != null) {
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
				if (AutoModpackTransformationService.EARLY_MC_VERSION != null) return AutoModpackTransformationService.EARLY_MC_VERSION;
				VersionInfo versionInfo = FMLLoader.versionInfo();
				if (versionInfo != null) return versionInfo.mcVersion();
				throw new IllegalStateException("Minecraft version is not available yet");
			}

			return null;
		}

		ModInfo modInfo = FMLLoader.getLoadingModList().getMods().stream().filter(mod -> mod.getModId().equals(modId)).findFirst().orElse(null);

		if (modInfo == null) return null;

		return modInfo.getVersion().toString();
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLLoader.isProduction();
	}
}
