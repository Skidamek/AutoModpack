package pl.skidam.automodpack_loader_core_forge_47;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.forgespi.locating.IModFile;

import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_loader_core_forge.LazyModLocatorBase;
import pl.skidam.automodpack_loader_core_forge.LazyModLocatorView;

/** The 1.19+ seam: forgespi's {@code createMod} wraps the result in IModLocator.ModFileOrException here. */
@SuppressWarnings("unused")
public class LazyModLocator extends LazyModLocatorBase implements LazyModLocatorView {
	@Override
	public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
		// Second line of defense behind ModLocatorDispatcher's generation pick; only 1.19+ may act.
		if (!GenerationProbes.FORGE_FML47) return List.of();
		// Only the embedded impl belongs to this early pass: Forge's JarInJar locator runs after it
		// and has to expand the impl's nested MixinExtras. The hosted jars' dependency locators run
		// from HostedDependencyLocator, appended after every native locator - see
		// EarlyServiceLayer#appendHostedDependencyLocator.
		var list = new ArrayList<IModFile>(1);
		addEmbeddedMod(list);
		return list;
	}

	@Override
	protected IModFile embeddedMod(Path modPath) {
		var mod = createMod(modPath);
		return mod.file();
	}
}
