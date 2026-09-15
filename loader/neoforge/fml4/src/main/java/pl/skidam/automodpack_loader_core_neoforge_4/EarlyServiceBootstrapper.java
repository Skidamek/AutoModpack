package pl.skidam.automodpack_loader_core_neoforge_4;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.Preload;
import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.TargetId;
import pl.skidam.automodpack_loader_core_modlauncher.EarlyServiceBridgePlugin;
import pl.skidam.automodpack_loader_core_neoforge_4.loader.LoaderManager;
import pl.skidam.automodpack_loader_core_neoforge_4.mods.ModpackLoader;

/**
 * Cross-generation linkage: the universal outer jar registers this class and the 21.10+ bootstrapper
 * under the same GraphicsBootstrapper service. That interface declares the identical abstract pair
 * {@code name()}/{@code bootstrap(String[])} in neoforgespi 4.x, 10.x and 11.x, and this class's only
 * supertype beyond it is Object - so it loads on every NeoForge generation, and the
 * {@link GenerationProbes#NEOFORGE_FML4} guard no-ops it wherever the flat-classloader generation runs.
 *
 * <p>
 * "Loads on every generation" is about LINKAGE, not just execution: ServiceLoader links every provider
 * it instantiates, and verification resolves the types named in a class's own bytecode - so everything
 * generation-specific (SecureJar, cpw.mods.cl, the module-layer build) lives in {@link
 * EarlyServiceLayer}, which only the fml4 generation ever executes into and therefore ever links. An
 * {@code if}-guard alone cannot protect that: linking happens before any body runs.
 */
public class EarlyServiceBootstrapper implements GraphicsBootstrapper {

	public static volatile String EARLY_MC_VERSION;
	public static volatile String EARLY_NEOFORGE_VERSION;
	// FMLLoader.getDist() is unreliable this early, so we read dist from --launchTarget instead;
	// an incorrect dist here makes Preload.updateAll() skip populating ModpackLoader.modsToLoad.
	public static volatile Boolean EARLY_IS_CLIENT;

	@Override
	public String name() {
		return "automodpack";
	}

	@Override
	public void bootstrap(String[] arguments) {
		// Coexists with the 21.10+ bootstrapper in the universal outer jar; only the ModLauncher-era generation may act.
		if (!GenerationProbes.NEOFORGE_FML4) return;

		EARLY_MC_VERSION = argValue(arguments, "--fml.mcVersion");
		EARLY_NEOFORGE_VERSION = argValue(arguments, "--fml.neoForgeVersion");
		String launchTarget = argValue(arguments, "--launchTarget");
		if (launchTarget != null) EARLY_IS_CLIENT = !launchTarget.toLowerCase(Locale.ROOT).contains("server");

		// TargetId throws when the id cannot be resolved: a launch without a target id must crash,
		// not silently run on an unknown combination.
		Constants.LOGGER.info("AutoModpack target: {}", TargetId.id("neoforge", EARLY_MC_VERSION));

		// Run our own update/reconcile step first: it decides what this launch loads, and the
		// early-service hosting below hosts only jars from that decision. Preload failures must crash
		// the launch; swallowing them would boot without the pack.
		ProgressMeter progress = StartupNotificationManager.prependProgressBar("[Automodpack] Preload", 0);
		new Preload(new LoaderManager(), new ModpackLoader());
		progress.complete();

		try {
			// Early-service hosting serves the client's active projection; a dedicated server has none.
			if (Constants.LOADER_MANAGER.getEnvironmentType() != LoaderManagerService.EnvironmentType.CLIENT) return;

			// Preload owns what this launch loads; host early services only for jars on that list, so a
			// projection that was skipped (no active state, pinned conflicts, standard-mods duplicates)
			// is never half-bootstrapped.
			List<Path> earlyServiceJars = ModpackLoader.modsToLoad.stream().filter(EarlyServiceLayer::eligibleForInPlace).toList();
			if (earlyServiceJars.isEmpty()) return;

			Constants.LOGGER.info("[AutoModpack] Bootstrapping {} early-service mod(s) from the active projection in place", earlyServiceJars.size());

			ModuleLayer serviceLayer = getClass().getModule().getLayer();
			if (serviceLayer == null) {
				Constants.LOGGER.warn("[AutoModpack] Not running on a module layer, cannot bootstrap early services in place");
				return;
			}

			EarlyServiceLayer.bootstrapJars(earlyServiceJars, serviceLayer, arguments);

			EarlyServiceLayer.instantiateTransformationServices();
			EarlyServiceBridgePlugin.registerFirst(EarlyServiceLayer::bridgeEarlyServicesToGameLayer);
		} catch (Throwable t) {
			Constants.LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
			throw new RuntimeException("AutoModpack early-service bootstrap failed", t);
		}
	}

	private static String argValue(String[] arguments, String name) {
		if (arguments != null) {
			String prefix = name + "=";
			for (int i = 0; i < arguments.length; i++) {
				if (name.equals(arguments[i]) && i + 1 < arguments.length) return arguments[i + 1];
				if (arguments[i].startsWith(prefix)) return arguments[i].substring(prefix.length());
			}
		}
		return null;
	}
}
