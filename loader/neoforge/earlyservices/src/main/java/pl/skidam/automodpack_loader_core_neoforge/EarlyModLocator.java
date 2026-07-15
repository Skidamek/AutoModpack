package pl.skidam.automodpack_loader_core_neoforge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;

import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

@SuppressWarnings("unused")
public class EarlyModLocator implements IModFileCandidateLocator {

	@Override
	public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {

		// Preload runs from EarlyServiceBootstrapper's GraphicsBootstrapper phase, which always
		// fires before mod discovery, so ModpackLoader.modsToLoad is already populated here.
		List<Path> earlyServiceJars = new ArrayList<>();
		for (Path path : ModpackLoader.modsToLoad) {
			// A standalone early-service jar (own root neoforge.mods.toml, e.g. modern Sodium) is
			// added as a regular mod file - its classes are already visible via FMLLoader's chain
			// (EarlyServiceBootstrapper). A split-jar shim (real mod lives in a nested jar found by
			// its own IModFileCandidateLocator/IDependencyLocator) is NOT itself a mod file and must
			// not be added directly; only its locators are replayed.
			if (EarlyServiceLayer.isEarlyServiceJar(path)) {
				if (EarlyServiceLayer.isStandaloneModFile(path)) {
					pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
					try {
						JarContents jarContents = JarContents.ofPath(path);
						pipeline.readModFile(jarContents, ModFileDiscoveryAttributes.DEFAULT);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}

				// Run IModFileReader first so custom-format candidates from the locators below
				// can be interpreted by it.
				EarlyServiceLayer.runModFileReaders(path, pipeline);
				earlyServiceJars.add(path);
				continue;
			}

			pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
			try {
				JarContents jarContents = JarContents.ofPath(path);
				pipeline.readModFile(jarContents, ModFileDiscoveryAttributes.DEFAULT);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		// Replay all early-service candidate locators together, priority-ordered (see the method).
		EarlyServiceLayer.runCandidateLocators(earlyServiceJars, context, pipeline);
	}

	@Override
	public int getPriority() {
		return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
	}
}
