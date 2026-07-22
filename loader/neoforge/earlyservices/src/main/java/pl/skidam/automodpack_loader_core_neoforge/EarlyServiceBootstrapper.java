package pl.skidam.automodpack_loader_core_neoforge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.utils.EarlyServiceScan;
import pl.skidam.automodpack_loader_core.Preload;

public class EarlyServiceBootstrapper implements GraphicsBootstrapper {

	// FMLLoader.getCurrent().getVersionInfo() is still null this early, so LoaderManager can't
	// get the MC/loader version from it during Preload's initializeConstants(). ModLauncher has
	// both on the command line (`--fml.mcVersion ... --fml.neoForgeVersion ...`), so capture them
	// here - the one place with access to `arguments` - before Preload runs.
	public static volatile String EARLY_MC_VERSION;
	public static volatile String EARLY_NEOFORGE_VERSION;
	// FMLLoader.getCurrent().getDist() is also unreliable this early: reading it too early can
	// silently return SERVER on a genuine CLIENT launch, so Preload.updateAll() takes its
	// dedicated-server-only branch. NeoForge's launchTarget naming convention
	// (e.g. "forgeclient"/"forgeserver") reliably encodes dist instead.
	public static volatile Boolean EARLY_IS_CLIENT;

	@Override
	public String name() {
		return "automodpack";
	}

	@Override
	public void bootstrap(String[] arguments) {
		try {
			EARLY_MC_VERSION = argValue(arguments, "--fml.mcVersion");
			EARLY_NEOFORGE_VERSION = argValue(arguments, "--fml.neoForgeVersion");
			String launchTarget = argValue(arguments, "--launchTarget");
			if (launchTarget != null) EARLY_IS_CLIENT = !launchTarget.toLowerCase(Locale.ROOT).contains("server");

			// Run the update/reconcile step before anything below reads the modpack folder, so an
			// update that changes which mods are early-service mods is reflected in the same boot.
			// This also publishes Constants.selectedModpackDir / Constants.MODS_DIR.
			ProgressMeter progress = StartupNotificationManager.prependProgressBar("[Automodpack] Preload", 0);
			new Preload();
			progress.complete();

			// Set by Preload only when a modpack is selected on a client - null means nothing to do.
			Path modpackMods = Constants.selectedModpackDir == null ? null : Constants.selectedModpackDir.resolve("mods");
			if (modpackMods == null || !Files.isDirectory(modpackMods)) return;

			List<Path> earlyServiceJars = EarlyServiceScan.eligibleJars(modpackMods, EarlyServiceLayer::eligibleForInPlace);

			if (earlyServiceJars.isEmpty()) return;

			Constants.LOGGER.info("[AutoModpack] Bootstrapping {} early-service mod(s) from the modpack folder in place", earlyServiceJars.size());

			ClassLoader childLoader = appendToFmlClassLoaderChain(earlyServiceJars);
			if (childLoader == null) {
				// Batched append failed - e.g. one unreadable jar poisoning the whole list. Retry
				// each jar individually so only the jars that actually fail stay out.
				List<Path> appended = new ArrayList<>();
				for (Path jar : earlyServiceJars) {
					ClassLoader loader = appendToFmlClassLoaderChain(List.of(jar));
					if (loader != null) {
						childLoader = loader;
						appended.add(jar);
					}
				}
				earlyServiceJars = appended;
				if (earlyServiceJars.isEmpty()) return;
			}

			EarlyServiceLayer.register(earlyServiceJars);

			for (Path jar : earlyServiceJars) {
				for (String impl : EarlyServiceLayer.serviceImpls(jar, EarlyServiceLayer.GRAPHICS_BOOTSTRAPPER_SERVICE)) {
					try {
						GraphicsBootstrapper bootstrapper = (GraphicsBootstrapper) Class.forName(impl, true, childLoader).getDeclaredConstructor()
								.newInstance();
						Constants.LOGGER.debug("[AutoModpack] Invoking in-place GraphicsBootstrapper {} ({}) from {}", impl, bootstrapper.name(),
								jar.getFileName());
						bootstrapper.bootstrap(arguments);
					} catch (Throwable t) {
						Constants.LOGGER.error("[AutoModpack] In-place GraphicsBootstrapper {} from {} failed", impl, jar.getFileName(), t);
					}
				}
			}
		} catch (Throwable t) {
			Constants.LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
		}
	}

	/**
	 * Registers these jars exactly like {@code FMLLoader.loadEarlyServices()}: each path becomes a
	 * native early-service mod file, its contents grow FMLLoader's flat classloader chain, and the mod
	 * file joins {@code earlyServicesJars} so dependency locators can inspect its nested jars later.
	 * FML keeps all three operations private, so this mirrors them through reflection.
	 *
	 * <p>
	 * This also bridges to the game layer: {@code FMLLoader} later builds the GAME
	 * {@code TransformingClassLoader} with {@code setFallbackClassLoader(currentClassLoader)}, and
	 * since this grows that same {@code currentClassLoader} chain first, game code resolves these
	 * jars' classes through that native fallback with no further action needed.
	 */
	private ClassLoader appendToFmlClassLoaderChain(List<Path> jars) {
		try {
			Class<?> fmlLoaderClass = Class.forName("net.neoforged.fml.loading.FMLLoader");
			Object current = fmlLoaderClass.getMethod("getCurrent").invoke(null);

			Class<?> earlyServiceDiscovery = Class.forName("net.neoforged.fml.loading.EarlyServiceDiscovery");
			Method createEarlyServiceModFile = earlyServiceDiscovery.getDeclaredMethod("createEarlyServiceModFile", Path.class);
			createEarlyServiceModFile.setAccessible(true);

			Class<?> modFileClass = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFile");
			Method getContents = modFileClass.getMethod("getContents");
			List<Object> earlyServiceModFiles = new ArrayList<>(jars.size());
			List<Object> jarContentsList = new ArrayList<>(jars.size());
			for (Path jar : jars) {
				Object modFile = createEarlyServiceModFile.invoke(null, jar);
				earlyServiceModFiles.add(modFile);
				jarContentsList.add(getContents.invoke(modFile));
			}

			Method appendLoader = fmlLoaderClass.getDeclaredMethod("appendLoader", String.class, List.class);
			appendLoader.setAccessible(true);
			appendLoader.invoke(current, "automodpack modpack early services", jarContentsList);

			Field earlyServicesJars = fmlLoaderClass.getDeclaredField("earlyServicesJars");
			earlyServicesJars.setAccessible(true);
			@SuppressWarnings("unchecked")
			List<Object> registered = (List<Object>) earlyServicesJars.get(current);
			registered.addAll(earlyServiceModFiles);

			Method getCurrentClassLoader = fmlLoaderClass.getMethod("getCurrentClassLoader");
			return (ClassLoader) getCurrentClassLoader.invoke(current);
		} catch (Throwable t) {
			Constants.LOGGER.error("[AutoModpack] Could not append early-service jar(s) {} to FMLLoader's classloader chain",
					jars.stream().map(Path::getFileName).toList(), t);
			return null;
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
