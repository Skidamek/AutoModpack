package pl.skidam.automodpack_loader_core_forge_40;

import java.nio.file.Path;
import java.util.List;

import net.minecraftforge.forgespi.locating.IModFile;

import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_loader_core_forge.LazyModLocatorBase;
import pl.skidam.automodpack_loader_core_forge.LazyModLocatorView;

/** The 1.18.2 seam: forgespi's {@code createMod} still wraps the result in an Optional here. */
@SuppressWarnings("unused")
public class LazyModLocator extends LazyModLocatorBase implements LazyModLocatorView {
	@Override
	public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
		// Second line of defense behind ModLocatorDispatcher's generation pick; only 1.18.2 may act.
		if (!GenerationProbes.FORGE_FML40) return List.of();
		return super.scanMods(loadedMods);
	}

	@Override
	protected IModFile embeddedMod(Path modPath) {
		// requires securejarhandler 1.0.8 or higher so forge 40.2.3 and up
		var mod = createMod(modPath);
		return mod.orElseThrow();
	}
}
