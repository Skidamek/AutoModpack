package pl.skidam.automodpack_loader_core_fabric;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.nio.file.Path;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;

import pl.skidam.automodpack_core.Preload;
import pl.skidam.automodpack_core.loader.ImplStore;
import pl.skidam.automodpack_core.loader.TargetId;
import pl.skidam.automodpack_loader_core_fabric.loader.LoaderManager;
import pl.skidam.automodpack_loader_core_fabric.mods.ImplMount;
import pl.skidam.automodpack_loader_core_fabric.mods.ModpackLoader;
import pl.skidam.automodpack_loader_fabric_shared.FabricLoaderMods;

public class FabricLanguageAdapter implements LanguageAdapter {

	public FabricLanguageAdapter() throws IllegalAccessException {
		FabricLoaderMods.install();
		LoaderManager loaderManager = new LoaderManager();
		mountImpl(loaderManager);
		new Preload(loaderManager, new ModpackLoader());
	}

	/**
	 * Surfaces the one jar's impl for this target explicitly (no {@code jars} metadata anymore): select it
	 * into the instance's impl cache and add it as a mod before {@link Preload}. This runs mid
	 * {@code FabricLoaderImpl#load()}, so the loader's own passes pick the impl up like any late-added
	 * modpack mod: access wideners, mixins and entrypoints all bootstrap after {@code load()} returns.
	 */
	private static void mountImpl(LoaderManager loaderManager) {
		// TargetId throws when the id cannot be resolved: a launch without a target id must crash,
		// not silently run on an unknown combination.
		String targetId = TargetId.id("fabric", loaderManager.getModVersion("minecraft"));
		LOGGER.info("AutoModpack target: {}", targetId);

		boolean client = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
		try {
			Path implJar = ImplStore.select(FabricLanguageAdapter.class, targetId, client);
			ImplMount.mount(implJar);
		} catch (Exception e) {
			throw new RuntimeException("Failed to stage or mount the AutoModpack impl jar", e);
		}
	}

	@Override
	public <T> T create(ModContainer mod, String value, Class<T> type) {
		throw new UnsupportedOperationException("AutoModpack");
	}
}
