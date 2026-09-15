package pl.skidam.automodpack_loader_core_fabric;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Path;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;

import pl.skidam.automodpack_core.Preload;
import pl.skidam.automodpack_core.loader.NestedImpl;
import pl.skidam.automodpack_core.loader.TargetId;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.storage.StoragePaths;
import pl.skidam.automodpack_loader_core_fabric.loader.LoaderManager;
import pl.skidam.automodpack_loader_core_fabric.mods.ImplMount;
import pl.skidam.automodpack_loader_core_fabric.mods.ModpackLoader;
import pl.skidam.automodpack_loader_fabric_shared.FabricLoaderMods;

public class FabricLanguageAdapter implements LanguageAdapter {

	public FabricLanguageAdapter() throws IllegalAccessException {
		FabricLoaderMods.install();
		LoaderManager loaderManager = new LoaderManager();
		mountImpl(loaderManager);
		new Preload(loaderManager, new ModpackLoader(loaderManager));
	}

	/**
	 * Surfaces the outer's nested impl jar explicitly (no {@code jars} metadata anymore): extract it
	 * to a real file and add it as a mod before {@link Preload}. This runs mid
	 * {@code FabricLoaderImpl#load()}, so the loader's own passes pick the impl up like any late-added
	 * modpack mod: access wideners, mixins and entrypoints all bootstrap after {@code load()} returns.
	 */
	private static void mountImpl(LoaderManager loaderManager) {
		// TargetId throws when the id cannot be resolved: a launch without a target id must crash,
		// not silently run on an unknown combination.
		LOGGER.info("AutoModpack target: {}", TargetId.id("fabric", loaderManager.getModVersion("minecraft")));

		boolean client = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
		Path cacheDir = GameDirectory.current().resolve(client ? StoragePaths.CLIENT_IMPL_CACHE_DIR : StoragePaths.SERVER_IMPL_CACHE_DIR);
		try {
			Path implJar = NestedImpl.extract(FabricLanguageAdapter.class, cacheDir);
			ImplMount.mount(implJar, loaderManager.getLoaderVersion());
		} catch (IOException e) {
			throw new RuntimeException("Failed to extract the AutoModpack impl jar", e);
		}
	}

	@Override
	public <T> T create(ModContainer mod, String value, Class<T> type) {
		throw new UnsupportedOperationException("AutoModpack");
	}
}
