package pl.skidam.automodpack_loader_core_forge;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;

import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;

/**
 * Surfaces this launch's projected mods as a mod file, then replays the early-service jars' candidate
 * locators (see {@link EarlyServiceLayer}). Version-agnostic except for the {@code scanMods()} return
 * element ({@code IModFile} on 1.18.2, {@code IModLocator.ModFileOrException} on 1.19+), which stays
 * in the per-version subclasses since this module compiles once against a single forgespi generation.
 * Lives in the earlyservices module like {@link LazyModLocatorBase}; this class is not ServiceLoaded -
 * {@link ModLocatorDispatcher} instantiates the subclass matching {@link GenerationProbes}.
 */
public abstract class EarlyModLocatorBase extends AbstractJarFileModLocator {

	private final boolean active;

	protected EarlyModLocatorBase(boolean active) {
		this.active = active;
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {}

	@Override
	public String name() {
		return "automodpack";
	}

	@Override
	public Stream<Path> scanCandidates() {
		// Second line of defense behind ModLocatorDispatcher's generation pick; only the running generation may act.
		if (!active) return Stream.empty();

		// Preload and the early-service child-layer bootstrap run from
		// AutoModpackTransformationService#onLoad, before this IModLocator pass, so
		// ModpackLoader.modsToLoad and EarlyServiceLayer's registered jars are already populated.
		//
		// Early-service jars must NOT load as mods here: native Forge excludes them from mod
		// discovery too, and their real mod arrives through their own IModLocator, replayed in
		// scanMods() below - loading the outer jar too would double-load it (shared modId).
		return ModpackLoader.modsToLoad.stream().filter(path -> !EarlyServiceLayer.isEarlyServiceJar(path));
	}
}
