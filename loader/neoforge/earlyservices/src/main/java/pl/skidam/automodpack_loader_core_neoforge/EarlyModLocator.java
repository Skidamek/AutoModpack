package pl.skidam.automodpack_loader_core_neoforge;

import java.nio.file.Path;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

/**
 * Cross-generation linkage: the universal outer jar registers this class and the fml4 locator under
 * the same IModFileCandidateLocator service, whose single abstract {@code findCandidates} (plus the
 * IOrderedProvider parent) is identical in neoforgespi 4.x, 10.x and 11.x, and this class's only
 * supertype beyond it is Object - so it loads on every NeoForge generation, and the
 * {@link GenerationProbes#NEOFORGE_EARLYSERVICES} guard no-ops it wherever the ModLauncher-era
 * generation runs. ILaunchContext/IDiscoveryPipeline descriptors themselves differ per generation but
 * are never resolved by loading this class, and the fml.jarcontents types in the body sit behind the
 * guard.
 */
@SuppressWarnings("unused")
public class EarlyModLocator implements IModFileCandidateLocator {

	@Override
	public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		// Coexists with the fml4 locators in the universal outer jar; only 21.10+ may act.
		if (!GenerationProbes.NEOFORGE_EARLYSERVICES) return;

		// Preload runs from EarlyServiceBootstrapper's GraphicsBootstrapper phase, which always
		// fires before mod discovery, so ModpackLoader.modsToLoad is already populated here.
		for (Path path : ModpackLoader.modsToLoad) {
			// A standalone early-service jar is a regular mod file. A split service shim is not:
			// its candidate/dependency locator was discovered natively after the jar was appended to
			// FMLLoader's classloader chain and is responsible for contributing the real mod.
			if (EarlyServiceLayer.isEarlyServiceJar(path)) {
				if (!EarlyServiceLayer.isStandaloneModFile(path)) continue;
				try {
					IModFile modFile = pipeline.readModFile(JarContents.ofPath(path), ModFileDiscoveryAttributes.DEFAULT);
					if (modFile != null) pipeline.addModFile(modFile);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				continue;
			}

			pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
		}
	}

	@Override
	public int getPriority() {
		return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
	}
}
