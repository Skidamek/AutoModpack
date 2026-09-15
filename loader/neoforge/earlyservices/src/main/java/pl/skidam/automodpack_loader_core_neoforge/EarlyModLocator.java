package pl.skidam.automodpack_loader_core_neoforge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.Preload;
import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.TargetId;
import pl.skidam.automodpack_loader_core_neoforge.loader.LoaderManager;
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
 * On the flat-classloader generation this is also the Preload phase: FML consumes the launch
 * arguments to build its own state before the graphics bootstrap fires and registers the loader only
 * afterwards, so the graphics-bootstrap phase can know nothing about this launch. Candidate locators
 * run before every other discovery consumer and receive the {@link ILaunchContext} - the first point
 * where dist and versions are readable - so the early flow (state capture, Preload, early-service
 * hosting) lives here, keeping the order the graphics-bootstrap phase used to provide.
 */
public class EarlyModLocator implements IModFileCandidateLocator {

	// The launch facts every later phase reads: the graphics-bootstrap arguments carry none of these
	// on this generation, so they are filled in from the launch context before Preload runs.
	public static volatile String EARLY_MC_VERSION;
	public static volatile String EARLY_NEOFORGE_VERSION;
	// FMLLoader.getCurrent().getDist() throws before the loader is current (see makeCurrent ordering),
	// so the launch context's required distribution stands in for it while Preload runs.
	public static volatile Boolean EARLY_IS_CLIENT;

	@Override
	public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		// Coexists with the fml4 locators in the universal outer jar; only 21.10+ may act.
		if (!GenerationProbes.NEOFORGE_EARLYSERVICES) return;

		// The graphics-bootstrap arguments carry nothing on this generation (FML consumes them to
		// build its own state first), so dist and versions both come off the launch context here.
		EARLY_MC_VERSION = context.getVersions().mcVersion();
		EARLY_NEOFORGE_VERSION = context.getVersions().neoForgeVersion();
		EARLY_IS_CLIENT = context.getRequiredDistribution() == Dist.CLIENT;
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
			if (Constants.LOADER_MANAGER.getEnvironmentType() != LoaderManagerService.EnvironmentType.CLIENT) {
				addCandidates(context, pipeline);
				return;
			}

			// Preload owns what this launch loads; host early services only for jars on that list, so a
			// projection that was skipped (no active state, pinned conflicts, standard-mods duplicates)
			// is never half-bootstrapped.
			List<Path> earlyServiceJars = ModpackLoader.modsToLoad.stream().filter(EarlyServiceLayer::eligibleForInPlace).toList();
			if (!earlyServiceJars.isEmpty()) {
				Constants.LOGGER.info("[AutoModpack] Bootstrapping {} early-service mod(s) from the active projection in place", earlyServiceJars.size());
				hostInPlace(earlyServiceJars);
			}
			// Register every custom reader before adding any candidate, so a jar owned by a hosted
			// reader (a translated Fabric mod, for example) is read with it no matter which jar
			// discovery reaches first.
			EarlyServiceLayer.runModFileReaders(pipeline);
			addCandidates(context, pipeline);
		} catch (Throwable t) {
			Constants.LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
			throw new RuntimeException("AutoModpack early-service bootstrap failed", t);
		}
	}

	/** Adds this launch's projected mods to discovery; hosted early-service shims are skipped (their locators contribute the real mod). */
	private void addCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		List<Path> unclaimablePaths = new ArrayList<>();
		for (Path path : ModpackLoader.modsToLoad) {
			// A standalone early-service jar is a regular mod file. A split service shim is not:
			// its replayed candidate/dependency locator is responsible for contributing the real mod.
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

			// A path no native reader could turn into a mod file is exactly the Fabric-only jar a
			// replayed Connector-style locator owns - hand it to the connector additional locations.
			if (pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS).isEmpty()) {
				unclaimablePaths.add(path);
			}
		}
		ModpackLoader.configureConnectorFallback(unclaimablePaths);
		// Replay all hosted candidate locators together, priority-ordered (see the method).
		EarlyServiceLayer.runCandidateLocators(context, pipeline);
	}

	/**
	 * Appends the hosted jars to FMLLoader's classloader chain (see {@link #appendToFmlClassLoaderChain}),
	 * registers them for the service locators, and fires the jars' own GraphicsBootstrappers in place.
	 */
	private void hostInPlace(List<Path> earlyServiceJars) {
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

		EarlyServiceLayer.register(earlyServiceJars, childLoader);

		for (Path jar : earlyServiceJars) {
			for (String impl : EarlyServiceLayer.serviceImpls(jar, EarlyServiceLayer.GRAPHICS_BOOTSTRAPPER_SERVICE)) {
				try {
					GraphicsBootstrapper bootstrapper = (GraphicsBootstrapper) Class.forName(impl, true, childLoader).getDeclaredConstructor()
							.newInstance();
					Constants.LOGGER.debug("[AutoModpack] Invoking in-place GraphicsBootstrapper {} ({}) from {}", impl, bootstrapper.name(),
							jar.getFileName());
					bootstrapper.bootstrap(new String[0]);
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

	@Override
	public int getPriority() {
		return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
	}
}
