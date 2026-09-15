package pl.skidam.automodpack_loader_core_forge_47;

import java.nio.file.Path;

import net.minecraftforge.forgespi.locating.IModFile;

import pl.skidam.automodpack_loader_core_forge.LazyModLocatorBase;
import pl.skidam.automodpack_loader_core_forge.LazyModLocatorView;

/** The 1.19+ seam: forgespi's {@code createMod} wraps the result in IModLocator.ModFileOrException here. */
@SuppressWarnings("unused")
public class LazyModLocator extends LazyModLocatorBase implements LazyModLocatorView {
	@Override
	protected IModFile embeddedMod(Path modPath) {
		var mod = createMod(modPath);
		return mod.file();
	}
}
