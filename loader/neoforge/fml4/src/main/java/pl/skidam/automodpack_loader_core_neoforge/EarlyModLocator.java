package pl.skidam.automodpack_loader_core_neoforge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;

import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

@SuppressWarnings("unused")
public class EarlyModLocator implements IModFileCandidateLocator {

	@Override
	public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		// Preload runs from EarlyServiceBootstrapper's GraphicsBootstrapper phase, before mod
		// discovery, so ModpackLoader.modsToLoad is already populated by the time we get here.
		List<Path> earlyServiceJars = new ArrayList<>();
		for (Path path : ModpackLoader.modsToLoad) {
			// Early-service jars (e.g. Sodium) sit on a child SERVICE layer; their real mod lives
			// in an inner jar loaded by their own candidate locator, so the outer jar must NOT be
			// added as a mod here - mirroring how the loader skips SERVICE-layer jars normally.
			if (EarlyServiceLayer.isEarlyServiceJar(path)) {
				boolean coremod = EarlyServiceLayer.isCoremodJar(path);
				// Coremod-only, not every standalone jar: mirrors native NeoForge, which excludes
				// any service-shipping mods/ jar from discovery except ICoreMod jars (ICoreMod is
				// not in that excluded set), so a standalone coremod still loads natively as a mod.
				if (coremod && EarlyServiceLayer.isStandaloneModFile(path)) {
					pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
				}
				// Every other early-service jar (a split jar, or a non-standalone coremod) keeps its
				// outer classes only on its child SERVICE layer; EarlyServiceBridgePlugin points the
				// GAME classloader at that layer instead of copying the outer classes to GAME.
				//
				// Forward IModFileReaders first so custom-format candidates from the locators below
				// can be interpreted by it.
				EarlyServiceLayer.runModFileReaders(path, pipeline);
				earlyServiceJars.add(path);
				continue;
			}

			pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
		}
		// Replay all early-service candidate locators together, priority-ordered (see the method).
		EarlyServiceLayer.runCandidateLocators(earlyServiceJars, context, pipeline);
	}

	@Override
	public int getPriority() {
		return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
	}
}
