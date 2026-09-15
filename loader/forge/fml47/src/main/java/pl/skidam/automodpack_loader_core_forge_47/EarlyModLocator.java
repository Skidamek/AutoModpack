package pl.skidam.automodpack_loader_core_forge_47;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.forgespi.locating.IModLocator;

import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_loader_core_forge.EarlyModLocatorView;
import pl.skidam.automodpack_loader_core_forge.EarlyServiceLayer;
import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;

@SuppressWarnings("unused")
public class EarlyModLocator extends AbstractJarFileModLocator implements EarlyModLocatorView {

	@Override
	public void initArguments(Map<String, ?> arguments) {}

	@Override
	public String name() {
		return "automodpack";
	}

	@Override
	public Stream<Path> scanCandidates() {
		// Second line of defense behind ModLocatorDispatcher's generation pick; only 1.19+ may act.
		if (!GenerationProbes.FORGE_FML47) return Stream.empty();

		// Preload and the early-service child-layer bootstrap run from
		// AutoModpackTransformationService#onLoad, before this IModLocator pass, so
		// ModpackLoader.modsToLoad and EarlyServiceLayer's registered jars are already populated.
		//
		// Early-service jars must NOT load as mods here: native Forge excludes them from mod
		// discovery too, and their real mod arrives through their own IModLocator/IDependencyLocator,
		// replayed in scanMods() below - loading the outer jar too would double-load it (shared modId).
		return ModpackLoader.modsToLoad.stream().filter(path -> !EarlyServiceLayer.isEarlyServiceJar(path));
	}

	@Override
	public List<IModLocator.ModFileOrException> scanMods() {
		// Second line of defense behind ModLocatorDispatcher's generation pick; only 1.19+ may act.
		if (!GenerationProbes.FORGE_FML47) return List.of();

		List<IModLocator.ModFileOrException> results = new ArrayList<>(super.scanMods());
		for (Path jar : EarlyServiceLayer.registeredJars()) {
			List<Object> extra = new ArrayList<>();
			EarlyServiceLayer.runCandidateLocators(jar, extra);
			for (Object o : extra) {
				results.add((IModLocator.ModFileOrException) o);
			}
		}
		return results;
	}
}
