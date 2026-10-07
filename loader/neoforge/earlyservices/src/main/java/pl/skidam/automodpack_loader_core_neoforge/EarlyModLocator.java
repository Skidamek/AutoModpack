package pl.skidam.automodpack_loader_core_neoforge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.loader.ConnectorFallback;
import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

/**
 * Cross-generation linkage: the universal outer jar registers this class and the fml4 locator under
 * the same IModFileCandidateLocator service, whose single abstract {@code findCandidates} (plus the
 * IOrderedProvider parent) is identical in neoforgespi 4.x, 10.x and 11.x, and this class's only
 * supertype beyond it is Object - so it loads on every NeoForge generation, and the
 * {@link GenerationProbes#NEOFORGE_EARLYSERVICES} guard no-ops it wherever the ModLauncher-era
 * generation runs. ILaunchContext/IDiscoveryPipeline descriptors themselves differ per generation but
 * are never resolved by loading this class, and the fml.jarcontents types in the body sit behind the
 * guard.
 *
 * <p>
 * Preload runs from {@link EarlyServiceBootstrapper}'s GraphicsBootstrapper phase, before mod
 * discovery, so {@code ModpackLoader.modsToLoad} is already populated by the time we get here.
 */
public class EarlyModLocator implements IModFileCandidateLocator {

	@Override
	public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		// Coexists with the fml4 locators in the universal outer jar; only 21.10+ may act.
		if (!GenerationProbes.NEOFORGE_EARLYSERVICES) return;

		try {
			addCandidates(context, pipeline);
		} catch (Throwable t) {
			Constants.LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
			throw new RuntimeException("AutoModpack early-service bootstrap failed", t);
		}
	}

	/** Adds this launch's projected mods to discovery; hosted early-service shims are skipped (their locators contribute the real mod). */
	private static void addCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		List<Path> unclaimablePaths = new ArrayList<>();
		for (Path path : ModpackLoader.modsToLoad) {
			// A standalone early-service jar is a regular mod file. A split service shim is not:
			// FML enumerates its candidate/dependency locator after we append at bootstrap.
			if (EarlyServiceLayer.isEarlyServiceJar(path)) {
				if (!EarlyServiceLayer.isStandaloneModFile(path)) continue;
				try {
					IModFile modFile = pipeline.readModFile(JarContents.ofPath(path), ModFileDiscoveryAttributes.DEFAULT);
					if (modFile != null) pipeline.addModFile(modFile);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				continue;
			}

			// Unclaimed Fabric jars plus Connector placeholders (FML-readable toml that Connector remaps).
			Optional<IModFile> claimed = pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
			if (claimed.isEmpty() || isConnectorPlaceholder(claimed.get())) unclaimablePaths.add(path);
		}
		ConnectorFallback.offer(unclaimablePaths);
	}

	private static boolean isConnectorPlaceholder(IModFile modFile) {
		return modFile.getModFileInfo() != null && ConnectorFallback.isPlaceholder(modFile.getModFileInfo().getFileProperties());
	}

	/**
	 * Appends the hosted jars to FMLLoader's classloader chain (see {@link #appendToFmlClassLoaderChain}),
	 * registers them for the service locators, and fires the jars' own GraphicsBootstrappers in place.
	 */
	static void hostInPlace(List<Path> earlyServiceJars, String[] arguments) {
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
			if (appended.size() != earlyServiceJars.size()) {
				List<Path> rejected = new ArrayList<>(earlyServiceJars);
				rejected.removeAll(appended);
				throw new RuntimeException("[AutoModpack] Cannot host early-service jars " + rejected + " on FML's classloader chain");
			}
			earlyServiceJars = appended;
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
	private static ClassLoader appendToFmlClassLoaderChain(List<Path> jars) {
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

	@Override
	public int getPriority() {
		return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
	}
}
