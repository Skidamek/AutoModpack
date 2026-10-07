package pl.skidam.automodpack_loader_core_neoforge_4;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import pl.skidam.automodpack_core.loader.ConnectorFallback;
import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_loader_core_neoforge_4.mods.ModpackLoader;

/**
 * Cross-generation linkage: the universal outer jar registers this class and the 21.10+ locator under
 * the same IModFileCandidateLocator service, whose single abstract {@code findCandidates} (plus the
 * IOrderedProvider parent) is identical in neoforgespi 4.x, 10.x and 11.x, and this class's only
 * supertype beyond it is Object - so it loads on every NeoForge generation, and the
 * {@link GenerationProbes#NEOFORGE_FML4} guard no-ops it wherever the flat-classloader generation runs.
 * ILaunchContext/IDiscoveryPipeline descriptors themselves differ per generation but are never resolved
 * by loading this class, and everything generation-specific in the body sits behind the guard.
 */
@SuppressWarnings("unused")
public class EarlyModLocator implements IModFileCandidateLocator {

	@Override
	public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		// Coexists with the 21.10+ locators in the universal outer jar; only the ModLauncher-era generation may act.
		if (!GenerationProbes.NEOFORGE_FML4) return;

		// Preload runs from EarlyServiceBootstrapper's GraphicsBootstrapper phase, before mod
		// discovery, so ModpackLoader.modsToLoad is already populated by the time we get here.
		List<Path> earlyServiceJars = new ArrayList<>();
		List<Path> candidatePaths = new ArrayList<>();
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
					candidatePaths.add(path);
				}
				// Every other early-service jar (a split jar, or a non-standalone coremod) keeps its
				// outer classes only on its child SERVICE layer; EarlyServiceBridgePlugin points the
				// GAME classloader at that layer instead of copying the outer classes to GAME.
				earlyServiceJars.add(path);
				continue;
			}

			candidatePaths.add(path);
		}

		// Register every custom reader before adding any candidate. A reader may belong to a jar that
		// sorts after the format it owns (Roxy translates Voxy, for example), so registering readers
		// while iterating paths would make discovery depend on filesystem order.
		for (Path path : earlyServiceJars) {
			EarlyServiceLayer.runModFileReaders(path, pipeline);
		}
		List<Path> connectorModLocations = new ArrayList<>();
		for (Path path : candidatePaths) {
			Optional<IModFile> modFile = pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
			if (modFile.isEmpty() || isConnectorPlaceholder(modFile.get())) connectorModLocations.add(path);
		}
		// Unclaimed Fabric jars plus Connector placeholders (FML-readable toml that Connector remaps). A custom reader that translated a Fabric jar into a real FML mod (Roxy) is not a placeholder and is not offered.
		ConnectorFallback.offer(connectorModLocations);
		// Replay all early-service candidate locators together, priority-ordered (see the method).
		EarlyServiceLayer.runCandidateLocators(earlyServiceJars, context, pipeline);
	}

	private static boolean isConnectorPlaceholder(IModFile modFile) {
		return modFile.getModFileInfo() != null && ConnectorFallback.isPlaceholder(modFile.getModFileInfo().getFileProperties());
	}

	@Override
	public int getPriority() {
		return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
	}
}
