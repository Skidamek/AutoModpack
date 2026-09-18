package pl.skidam.automodpack_loader_core_neoforge;

import java.nio.file.Path;
import java.util.List;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforgespi.locating.*;

import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_core.loader.ImplStore;

/**
 * Cross-generation linkage: the universal outer jar registers this class and the fml4 locator under
 * the same IDependencyLocator service, whose single abstract {@code scanMods(List, IDiscoveryPipeline)}
 * is identical in neoforgespi 4.x, 10.x and 11.x, and this class's only supertype beyond it is Object -
 * so it loads on every NeoForge generation, and the {@link GenerationProbes#NEOFORGE_EARLYSERVICES}
 * guard no-ops it wherever the ModLauncher-era generation runs. The fml.jarcontents types in the body
 * sit behind that guard.
 */
@SuppressWarnings("unused")
public class LazyModLocator implements IDependencyLocator {

	@Override
	public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
		// Coexists with the fml4 locators in the universal outer jar; only 21.10+ may act.
		if (!GenerationProbes.NEOFORGE_EARLYSERVICES) return;

		try {
			// The outer jar's nested impl carries no jarjar metadata anymore, so surface it explicitly.
			// This is the native Jar-in-Jar flow: extract the nested jar, read it as a mod file, add it.
			Path implJar = ImplStore.select(LazyModLocator.class, "neoforge");
			IModFile modFile = pipeline.readModFile(JarContents.ofPath(implJar), ModFileDiscoveryAttributes.DEFAULT);
			if (modFile != null) pipeline.addModFile(modFile);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		// FML enumerated dependency locators before the candidate phase appended the hosted jars, so
		// their own jar-in-jar locators never run natively - replay them here, in the dependency phase
		// they belong to (see EarlyServiceLayer.runDependencyLocators).
		EarlyServiceLayer.runDependencyLocators(loadedMods, pipeline);
	}

	@Override
	public int getPriority() {
		return IModFileCandidateLocator.LOWEST_SYSTEM_PRIORITY;
	}

}
