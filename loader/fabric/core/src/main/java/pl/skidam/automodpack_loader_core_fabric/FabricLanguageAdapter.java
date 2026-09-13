package pl.skidam.automodpack_loader_core_fabric;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;

import pl.skidam.automodpack_core.Preload;
import pl.skidam.automodpack_loader_core_fabric.loader.LoaderManager;
import pl.skidam.automodpack_loader_core_fabric.mods.ModpackLoader;
import pl.skidam.automodpack_loader_fabric_shared.FabricLoaderMods;

public class FabricLanguageAdapter implements LanguageAdapter {

	public FabricLanguageAdapter() throws IllegalAccessException {
		FabricLoaderMods.install();
		new Preload(new LoaderManager(), ModpackLoader::new);
	}

	@Override
	public <T> T create(ModContainer mod, String value, Class<T> type) {
		throw new UnsupportedOperationException("AutoModpack");
	}
}
