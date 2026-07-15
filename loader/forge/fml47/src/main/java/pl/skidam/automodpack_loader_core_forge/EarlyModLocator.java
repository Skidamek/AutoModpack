package pl.skidam.automodpack_loader_core_forge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.forgespi.locating.IModLocator;

import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;

@SuppressWarnings("unused")
public class EarlyModLocator extends AbstractJarFileModLocator {

	@Override
	public void initArguments(Map<String, ?> arguments) {}

	@Override
	public String name() {
		return "automodpack";
	}

	@Override
	public Stream<Path> scanCandidates() {
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
