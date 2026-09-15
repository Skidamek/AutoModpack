package pl.skidam.automodpack_loader_core_neoforge_4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.neoforged.neoforgespi.locating.*;

import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_core.loader.ImplStore;
import pl.skidam.automodpack_loader_core_neoforge_4.mods.ImplMount;
import pl.skidam.automodpack_loader_core_neoforge_4.mods.ModpackLoader;

/**
 * Cross-generation linkage: the universal outer jar registers this class and the 21.10+ locator under
 * the same IDependencyLocator service, whose single abstract {@code scanMods(List, IDiscoveryPipeline)}
 * is identical in neoforgespi 4.x, 10.x and 11.x, and this class's only supertype beyond it is Object -
 * so it loads on every NeoForge generation, and the {@link GenerationProbes#NEOFORGE_FML4} guard no-ops
 * it wherever the flat-classloader generation runs. The securejarhandler types live in
 * {@link ImplMount}, which only the fml4 generation ever links (loading a class does not link its
 * callees, so this class's bytecode stays verifiable on every generation).
 */
@SuppressWarnings("unused")
public class LazyModLocator implements IDependencyLocator {

	@Override
	public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
		// Coexists with the 21.10+ locators in the universal outer jar; only the ModLauncher-era generation may act.
		if (!GenerationProbes.NEOFORGE_FML4) return;

		try {
			// The outer jar's nested impl carries no jarjar metadata anymore, so surface it explicitly:
			// extract it where a real file is needed anyway and mount it as this launch's impl mod.
			pipeline.addModFile(ImplMount.createModFile(implJar()));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		// Replay the dependency locators of early-service jars (e.g. Ixeris) so their real
		// (inner) mod loads in place, from the active projection, without being copied to the
		// standard mods directory.
		List<Path> earlyServiceJars = new ArrayList<>();
		for (Path path : ModpackLoader.modsToLoad) {
			if (EarlyServiceLayer.isEarlyServiceJar(path)) earlyServiceJars.add(path);
		}
		EarlyServiceLayer.runDependencyLocators(earlyServiceJars, loadedMods, pipeline);
	}

	private static Path implJar() throws IOException {
		Boolean client = EarlyServiceBootstrapper.EARLY_IS_CLIENT;
		if (client == null) throw new IllegalStateException("AutoModpack cannot tell client from server before mounting the impl jar");
		return ImplStore.select(LazyModLocator.class, "neoforge", EarlyServiceBootstrapper.EARLY_MC_VERSION, client);
	}

	@Override
	public int getPriority() {
		return IModFileCandidateLocator.LOWEST_SYSTEM_PRIORITY;
	}
}
