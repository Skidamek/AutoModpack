package pl.skidam.automodpack_loader_core_forge;

import java.nio.file.Path;

import net.minecraftforge.forgespi.locating.IModFile;

/** The 1.19+ seam: forgespi's {@code createMod} wraps the result in IModLocator.ModFileOrException here. */
@SuppressWarnings("unused")
public class LazyModLocator extends LazyModLocatorBase {
	@Override
	protected IModFile embeddedMod(Path modPath) {
		var mod = createMod(modPath);
		return mod.file();
	}
}
