package pl.skidam.automodpack_loader_core_forge;

import java.nio.file.Path;

import net.minecraftforge.forgespi.locating.IModFile;

/** The 1.18.2 seam: forgespi's {@code createMod} still wraps the result in an Optional here. */
@SuppressWarnings("unused")
public class LazyModLocator extends LazyModLocatorBase {
	@Override
	protected IModFile embeddedMod(Path modPath) {
		// requires securejarhandler 1.0.8 or higher so forge 40.2.3 and up
		var mod = createMod(modPath);
		return mod.orElseThrow();
	}
}
