package pl.skidam.automodpack_loader_core_neoforge;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.lang.reflect.Field;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.coremod.ICoreMod;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

import pl.skidam.automodpack_core.loader.LoaderServiceFiles;
import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_loader_core_modlauncher.ModLauncherEarlyServiceBridge;

/**
 * Holds the per-jar child SERVICE module layers built by {@link EarlyServiceBootstrapper}
 * for NeoForge mods that ship "early services" inside the active projection, and
 * replays their mod-locating services (which only the loader's SERVICE layer would
 * normally run) into AutoModpack's own discovery so the mods load in place - without
 * being copied into the standard {@code mods/} directory.
 *
 * <p>
 * {@link EarlyServiceBootstrapper} fires the {@code GraphicsBootstrapper}s. This class
 * exposes the same correctly-named classloaders so {@link EarlyModLocator} can run each
 * jar's {@code IModFileCandidateLocator}s and {@link LazyModLocator} its
 * {@code IDependencyLocator}s, loading whatever inner mods they provide.
 *
 * <p>
 * The ModLauncher-family machinery this shares with legacy Forge - the child-layer registry,
 * the {@code ITransformationService} forwarding engine and the GAME-classloader bridge -
 * lives in {@link ModLauncherEarlyServiceBridge}.
 */
public final class EarlyServiceLayer {

	private EarlyServiceLayer() {}

	public static final String GRAPHICS_BOOTSTRAPPER_SERVICE = LoaderServicePaths.NEOFORGE_GRAPHICS_BOOTSTRAPPER;
	public static final String CANDIDATE_LOCATOR_SERVICE = LoaderServicePaths.NEOFORGE_CANDIDATE_LOCATOR;
	public static final String DEPENDENCY_LOCATOR_SERVICE = LoaderServicePaths.NEOFORGE_DEPENDENCY_LOCATOR;
	public static final String LANGUAGE_LOADER_SERVICE = LoaderServicePaths.NEOFORGE_LANGUAGE_LOADER;
	public static final String COREMOD_SERVICE = LoaderServicePaths.NEOFORGE_COREMOD;
	public static final String TRANSFORMATION_SERVICE = LoaderServicePaths.TRANSFORMATION_SERVICE;
	public static final String MOD_FILE_READER_SERVICE = LoaderServicePaths.NEOFORGE_MOD_FILE_READER;

	// Services that require active work to run in place (fired/invoked/forwarded by this class). A
	// jar must declare at least one of these at its root to be worth bootstrapping in place; these
	// are also exactly the services whose impl class names we read.
	static final List<String> ACTIVELY_RUN_SERVICES = List.of(GRAPHICS_BOOTSTRAPPER_SERVICE, CANDIDATE_LOCATOR_SERVICE, DEPENDENCY_LOCATOR_SERVICE,
			MOD_FILE_READER_SERVICE, COREMOD_SERVICE, TRANSFORMATION_SERVICE);

	// Every service we can host from the active projection, so a mod shipping only these never needs
	// copying: the actively-run ones above, plus language loaders (passive - picked up from the GAME
	// layer). Single source of truth for both the copy decision and the in-place bootstrapper.
	//
	// ImmediateWindowProvider is deliberately NOT here (though it IS in knownServices): NeoForge picks
	// the early-window provider and creates the window in the same call, before and out of reach of
	// anything we can do from the active projection, so a mod needing it must force-copy to mods/.
	public static final Set<String> HANDLEABLE_SERVICES = Stream.concat(ACTIVELY_RUN_SERVICES.stream(), Stream.of(LANGUAGE_LOADER_SERVICE))
			.collect(Collectors.toUnmodifiableSet());

	// Every service the running loader version actually handles, read from the loader itself
	// (TransformerDiscovererConstants.SERVICES, plus ICoreMod/IModLanguageLoader which FML handles
	// outside that set) rather than a hand-maintained cross-version list. The force-copy decision
	// counts only services in here: one the loader doesn't handle can't be fixed by copying either.
	private static final Set<String> HANDLED_SERVICES = computeHandledServices();

	/**
	 * Service files this loader version actually discovers/runs; superset of {@link #HANDLEABLE_SERVICES}.
	 * Used to narrow a jar's raw service set (see {@link #inspect}) so a legacy/removed SPI this
	 * loader version doesn't handle can't wrongly block {@link #eligibleForInPlace}.
	 */
	private static Set<String> knownServices() {
		return HANDLED_SERVICES;
	}

	private static Set<String> computeHandledServices() {
		Set<String> handled = new HashSet<>();
		try {
			Class<?> constants = Class.forName("net.neoforged.fml.loading.TransformerDiscovererConstants");
			Object services = constants.getField("SERVICES").get(null);
			if (services instanceof Set<?> names) {
				for (Object name : names) {
					// SERVICES holds dotted class names (Class.getName()); a service file is the same.
					handled.add("META-INF/services/" + String.valueOf(name).replace('/', '.'));
				}
			}
		} catch (Throwable t) {
			// The internal constant moved/renamed on this loader version: fall back to the services we
			// know NeoForge handles (the ones we host in place, plus the two we deliberately copy).
			LOGGER.warn("[AutoModpack] Could not read the loader's early-service list; using the built-in fallback", t);
			handled.addAll(HANDLEABLE_SERVICES);
			handled.add(MOD_FILE_READER_SERVICE);
			handled.add(LoaderServicePaths.NEOFORGE_IMMEDIATE_WINDOW_PROVIDER);
		}
		handled.add(COREMOD_SERVICE); // ICoreMod - collected by FML's coremod pass
		handled.add(LANGUAGE_LOADER_SERVICE); // IModLanguageLoader
		return Set.copyOf(handled);
	}

	// Maps each handled modpack jar to its child-layer classloader/layer. Populated by EarlyServiceBootstrapper.
	// The shared registry itself lives in ModLauncherEarlyServiceBridge.

	// Per-jar facts derived from a single jar mount, cached for the JVM's life (jar content is
	// immutable for the run). Without this the bootstrapper and both locators each re-open the
	// same jar to re-derive the same booleans/service lists - ~10 mounts per early-service jar.
	private record JarInfo(boolean activelyRunInPlace, // an ACTIVELY_RUN service exists at root
			Set<String> services, // known services at root + nested jarjar
			Map<String, List<String>> serviceImpls, // impl class names per ACTIVELY_RUN service
			boolean standalone, // root META-INF/neoforge.mods.toml present
			boolean coremod) {} // ships at least one ICoreMod impl

	private static final Map<Path, JarInfo> JAR_INFO = new ConcurrentHashMap<>();

	private static JarInfo info(Path jar) {
		return JAR_INFO.computeIfAbsent(ModLauncherEarlyServiceBridge.canonical(jar), EarlyServiceLayer::inspect);
	}

	private static JarInfo inspect(Path jar) {
		boolean activelyRun = false;
		Set<String> services = Set.of();
		Map<String, List<String>> impls = new HashMap<>();
		boolean standalone = false;
		try (FileSystem fs = FileSystems.newFileSystem(jar)) {
			// Scoped to what this loader version actually handles (knownServices()), so a legacy/
			// removed SPI (e.g. the old IModLocator) doesn't wrongly make an otherwise in-place-able
			// mod look unhandleable.
			services = FileInspection.getServices(fs, knownServices());
			standalone = Files.exists(fs.getPath("META-INF/neoforge.mods.toml"));
			for (String service : ACTIVELY_RUN_SERVICES) {
				if (Files.exists(fs.getPath(service))) {
					activelyRun = true;
					impls.put(service, LoaderServiceFiles.readImplementations(fs, service));
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[AutoModpack] Could not inspect {}; not handling it in place", jar.getFileName(), e);
		}
		boolean coremod = !impls.getOrDefault(COREMOD_SERVICE, List.of()).isEmpty();
		return new JarInfo(activelyRun, services, impls, standalone, coremod);
	}

	/** The impl class names of an {@link #ACTIVELY_RUN_SERVICES} service declared at the jar's root. */
	public static List<String> serviceImpls(Path jar, String serviceFile) {
		return info(jar).serviceImpls().getOrDefault(serviceFile, List.of());
	}

	/**
	 * Runs the {@code IModFileCandidateLocator}s declared inside an early-service jar
	 * against AutoModpack's discovery pipeline. This is how mods like Sodium load their
	 * real (inner) mod jar - the loader would otherwise only run this from its SERVICE
	 * layer, which AutoModpack never reaches.
	 */
	public static void runCandidateLocators(List<Path> jars, ILaunchContext context, IDiscoveryPipeline pipeline) {
		List<IModFileCandidateLocator> locators = new ArrayList<>();
		for (Path jar : jars) {
			ClassLoader cl = ModLauncherEarlyServiceBridge.classLoaderFor(jar);
			if (cl == null) continue;
			for (String impl : serviceImpls(jar, CANDIDATE_LOCATOR_SERVICE)) {
				try {
					locators.add((IModFileCandidateLocator) Class.forName(impl, true, cl).getDeclaredConstructor().newInstance());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] Failed to load candidate locator {} from {}", impl, jar.getFileName(), t);
				}
			}
		}
		// Run highest-priority first (IOrderedProvider order) across ALL early-service jars, so a
		// replayed locator's declared priority is honoured relative to the others; raw modsToLoad
		// (filesystem) order would silently drop it.
		locators.sort(Comparator.comparingInt(IModFileCandidateLocator::getPriority).reversed());
		for (IModFileCandidateLocator locator : locators) {
			try {
				LOGGER.debug("[AutoModpack] Running in-place candidate locator {} (priority {})", locator.getClass().getName(), locator.getPriority());
				locator.findCandidates(context, pipeline);
			} catch (Throwable t) {
				LOGGER.error("[AutoModpack] Failed to run candidate locator {}", locator.getClass().getName(), t);
			}
		}
	}

	/**
	 * Runs the {@code IDependencyLocator}s declared inside an early-service jar. This is
	 * how mods like Ixeris load their real (inner) mod jar.
	 */
	public static void runDependencyLocators(List<Path> jars, List<?> loadedMods, IDiscoveryPipeline pipeline) {
		@SuppressWarnings("unchecked")
		List<IModFile> mods = (List<IModFile>) loadedMods;
		List<IDependencyLocator> locators = new ArrayList<>();
		for (Path jar : jars) {
			ClassLoader cl = ModLauncherEarlyServiceBridge.classLoaderFor(jar);
			if (cl == null) continue;
			for (String impl : serviceImpls(jar, DEPENDENCY_LOCATOR_SERVICE)) {
				try {
					locators.add((IDependencyLocator) Class.forName(impl, true, cl).getDeclaredConstructor().newInstance());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] Failed to load dependency locator {} from {}", impl, jar.getFileName(), t);
				}
			}
		}
		locators.sort(Comparator.comparingInt(IDependencyLocator::getPriority).reversed());
		for (IDependencyLocator locator : locators) {
			try {
				LOGGER.debug("[AutoModpack] Running in-place dependency locator {} (priority {})", locator.getClass().getName(), locator.getPriority());
				locator.scanMods(mods, pipeline);
			} catch (Throwable t) {
				LOGGER.error("[AutoModpack] Failed to run dependency locator {}", locator.getClass().getName(), t);
			}
		}
	}

	/**
	 * Forwards an early-service jar's {@code IModFileReader}s into the live discovery pipeline, so a
	 * modpack-folder mod that ships a reader for a custom mod-file format can interpret candidates in
	 * place - no copy needed.
	 *
	 * <p>
	 * Unlike a locator, a reader isn't invoked directly: the pipeline consults its own
	 * {@code modFileReaders} list (built by {@code ModDiscoverer} from the SERVICE/PLUGIN layers,
	 * which a modpack jar never reaches) whenever it reads a candidate. There is no public API to add
	 * one, so we splice ours into that list by reflection - the loader is an automatic module, so
	 * plain {@code setAccessible} reaches it. We run from {@link EarlyModLocator} (highest priority),
	 * before the pipeline reads most candidates, and re-sort by {@code IOrderedProvider} priority so
	 * our reader keeps the loader's precedence contract.
	 */
	public static void runModFileReaders(Path jar, IDiscoveryPipeline pipeline) {
		ClassLoader cl = ModLauncherEarlyServiceBridge.classLoaderFor(jar);
		if (cl == null) return;
		List<Object> readers = new ArrayList<>();
		for (String impl : serviceImpls(jar, MOD_FILE_READER_SERVICE)) {
			try {
				readers.add(Class.forName(impl, true, cl).getDeclaredConstructor().newInstance());
			} catch (Throwable t) {
				LOGGER.error("[AutoModpack] Failed to instantiate IModFileReader {} from {}", impl, jar.getFileName(), t);
			}
		}
		if (readers.isEmpty()) return;

		try {
			// pipeline is ModDiscoverer.DiscoveryPipeline; reach its outer ModDiscoverer's reader list.
			Field outer = pipeline.getClass().getDeclaredField("this$0");
			outer.setAccessible(true);
			Object modDiscoverer = outer.get(pipeline);
			Field readersField = modDiscoverer.getClass().getDeclaredField("modFileReaders");
			readersField.setAccessible(true);
			@SuppressWarnings("unchecked")
			List<Object> current = (List<Object>) readersField.get(modDiscoverer);
			List<Object> merged = new ArrayList<>(current);
			merged.addAll(readers);
			// ModDiscoverer sorts readers by IOrderedProvider.getPriority(), highest first.
			merged.sort(Comparator.comparingInt(EarlyServiceLayer::providerPriority).reversed());
			readersField.set(modDiscoverer, List.copyOf(merged));
			LOGGER.debug("[AutoModpack] Forwarded {} in-place IModFileReader(s) from {} into mod discovery", readers.size(), jar.getFileName());
		} catch (Throwable t) {
			LOGGER.error("[AutoModpack] Could not forward IModFileReader(s) from {} into mod discovery; a mod relying on that reader may need copy-to-standard",
					jar.getFileName(), t);
		}
	}

	/** {@code IOrderedProvider.getPriority()} of a reader, or the default (0) if it can't be read. */
	private static int providerPriority(Object provider) {
		try {
			return (int) provider.getClass().getMethod("getPriority").invoke(provider);
		} catch (Throwable t) {
			return 0;
		}
	}

	/**
	 * Whether this jar's early services can all be run from the active projection. It must declare at
	 * its root at least one service we actively run in place (a {@code GraphicsBootstrapper}, a
	 * candidate/dependency locator, an {@code IModFileReader}, or a coremod's {@code ICoreMod}) AND
	 * ship no service outside {@link #HANDLEABLE_SERVICES}. Anything else is left for the
	 * copy-to-standard path rather than being half-loaded in place.
	 */
	public static boolean eligibleForInPlace(Path jar) {
		JarInfo info = info(jar);
		// Must actively run at least one service in place (root), and ship nothing we can't host.
		return info.activelyRunInPlace() && HANDLEABLE_SERVICES.containsAll(info.services());
	}

	/**
	 * Whether the jar is itself a loadable mod - it declares a root {@code neoforge.mods.toml} - as
	 * opposed to a thin outer jar whose real mod is nested or absent (e.g. Sodium's split jar, or
	 * Sinytra Connector's locator jar). A coremod jar that IS a standalone mod is added to the GAME
	 * layer as its real self (loading normally) and so is left out of the GAME-layer bridge; a
	 * non-standalone one has its outer classes bridged there instead.
	 */
	public static boolean isStandaloneModFile(Path jar) {
		return info(jar).standalone();
	}

	/**
	 * Instantiates each in-place jar's declared {@code ITransformationService}(s), from the earlywindow
	 * bootstrap phase (itself a {@code GraphicsBootstrapper.bootstrap()} callback). Does NOT call any
	 * lifecycle method on them - {@link AutoModpackTransformationService}, a real, natively
	 * ServiceLoader-discovered {@code ITransformationService} (see its class javadoc), drives
	 * {@code onLoad}/{@code initialize}/{@code beginScanning}/{@code completeScan}/{@code transformers}
	 * on these instances itself, forwarding each call at the exact moment ModLauncher's own {@code
	 * TransformationServicesHandler} makes it - which is always correctly ordered relative to
	 * everything else (window-provider assignment, other services' onLoad/initialize) because that
	 * ordering is ModLauncher's own native invariant, not something we have to reconstruct by picking a
	 * hook point ourselves.
	 */
	public static void instantiateTransformationServices() {
		ModLauncherEarlyServiceBridge.forEachRegistered((jar, cl) -> {
			for (String impl : serviceImpls(jar, TRANSFORMATION_SERVICE)) {
				try {
					ITransformationService service = (ITransformationService) Class.forName(impl, true, cl).getDeclaredConstructor().newInstance();
					ModLauncherEarlyServiceBridge.addTransformationService(jar, service);
					LOGGER.debug("[AutoModpack] Instantiated in-place transformation service {} ({})", impl, jar.getFileName());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] Failed to instantiate in-place transformation service {} from {}", impl, jar.getFileName(), t);
				}
			}
		});
	}

	/** Forwards ModLauncher's native {@code onLoad} call to each in-place transformation service. */
	static void forwardOnLoad(IEnvironment env, Set<String> otherServices) {
		ModLauncherEarlyServiceBridge.forEachTransformationService("onLoad", service -> service.onLoad(env, otherServices));
	}

	/** Forwards ModLauncher's native {@code initialize} call to each in-place transformation service. */
	static void forwardInitialize(IEnvironment env) {
		ModLauncherEarlyServiceBridge.forEachTransformationService("initialize", service -> service.initialize(env));
	}

	/**
	 * Forwards ModLauncher's native {@code beginScanning} call to each in-place transformation service,
	 * merging their returned resources - previously impossible to run at all, since we had no hook at
	 * ModLauncher's real {@code runScanningTransformationServices} time; those resources now reach the
	 * PLUGIN/GAME layers exactly as they would if the jar sat on the real SERVICE layer.
	 */
	static List<ITransformationService.Resource> forwardBeginScanning(IEnvironment env) {
		return ModLauncherEarlyServiceBridge.collectFromTransformationServices("beginScanning", service -> service.beginScanning(env));
	}

	/**
	 * Forwards ModLauncher's {@code completeScan} to each in-place transformation service, merging
	 * their layer resources. Called by {@link AutoModpackTransformationService} when ModLauncher runs
	 * {@code triggerScanCompletion} - so the returned jars (e.g. Connector's {@code FabricASMFixer}
	 * generated classes and its {@code authlib}/{@code brigadier} moves) are added to the GAME/PLUGIN
	 * layers as they are built.
	 */
	static List<ITransformationService.Resource> forwardCompleteScan(IModuleLayerManager layerManager) {
		return ModLauncherEarlyServiceBridge.collectFromTransformationServices("completeScan", service -> service.completeScan(layerManager));
	}

	/**
	 * Forwards each in-place transformation service's {@code transformers()}, called by {@link
	 * AutoModpackTransformationService#transformers()} - a separate pass from {@link
	 * #collectForwardedTransformers()} (which forwards {@code ICoreMod} transformers for {@link
	 * AutoModpackCoreMod}), so each SPI's transformers are collected exactly once, by the AutoModpack
	 * service that is itself natively discovered the same way the real SPI would be.
	 */
	static List<ITransformer<?>> collectTransformationServiceTransformers() {
		return ModLauncherEarlyServiceBridge.collectFromTransformationServices("transformers", service -> new ArrayList<>(service.transformers()));
	}

	/**
	 * Instantiates the {@code ICoreMod}s shipped by the registered early-service jars (from their
	 * child SERVICE layer, where the outer classes live) and collects their transformers. Called by
	 * {@link AutoModpackCoreMod} during FML's {@code transformers()} pass.
	 *
	 * <p>
	 * This is how a modpack-folder coremod (e.g. Sinytra Connector, whose own mixins rely on its
	 * coremod to remap their {@code @Shadow} targets) runs in place. {@code ICoreMod} is a NeoForge/FML
	 * SPI, not a ModLauncher one - FML's own {@code transformers()} pass collects it directly (not via
	 * native {@code ServiceLoader} discovery the way {@code ITransformationService} is), and a modpack
	 * jar's coremod is never part of that scan. AutoModpack, however, sits on the SERVICE layer and IS
	 * scanned - so it forwards the modpack coremods' transformers as its own. (A modpack jar's own
	 * {@code ITransformationService} transformers are forwarded separately, by {@link
	 * AutoModpackTransformationService#transformers()} - see {@link #collectTransformationServiceTransformers()}.)
	 */
	public static List<ITransformer<?>> collectForwardedTransformers() {
		List<ITransformer<?>> transformers = new ArrayList<>();
		for (Path jar : ModLauncherEarlyServiceBridge.registeredJars()) {
			ClassLoader cl = ModLauncherEarlyServiceBridge.classLoaderFor(jar);

			// A coremod's transformers (e.g. Sinytra Connector's @Shadow name->SRG remap that its
			// own mixins need to apply). Most early-service jars ship none.
			for (String impl : serviceImpls(jar, COREMOD_SERVICE)) {
				try {
					ICoreMod coremod = (ICoreMod) Class.forName(impl, true, cl).getDeclaredConstructor().newInstance();
					int before = transformers.size();
					for (ITransformer<?> transformer : coremod.getTransformers()) {
						transformers.add(transformer);
					}
					LOGGER.debug("[AutoModpack] Forwarding {} transformer(s) from in-place coremod {} ({})", transformers.size() - before, impl,
							jar.getFileName());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] Failed to run in-place coremod {} from {}", impl, jar.getFileName(), t);
				}
			}
			// A transformation service's transformers are forwarded separately, by
			// AutoModpackTransformationService#transformers() (see collectTransformationServiceTransformers) -
			// that service is itself natively ServiceLoader-discovered the same way a real
			// ITransformationService would be, so ModLauncher calls its transformers() directly; forwarding
			// them again here would register every one of them twice.
		}
		return transformers;
	}

	/**
	 * Points the GAME classloader at each in-place early-service jar's child SERVICE layer - the
	 * layer its {@code GraphicsBootstrapper} actually fired on - so the outer classes resolve there,
	 * in place, with no GAME-library copy.
	 *
	 * <p>
	 * The inner mod (on the GAME layer) references its outer jar's classes; natively those resolve
	 * because the outer jar sits on the SERVICE layer, a GAME ancestor. Our child layer is only a
	 * sibling, so we add the child as a {@code parentLoaders} delegate for each of the outer jar's
	 * packages (dropping any stale GAME {@code packageLookup} entry). Every outer class - whether
	 * referenced structurally during Mixin config prep or read for its static state at runtime -
	 * then resolves to the single, already-initialised child copy: no second class, no split state.
	 *
	 * <p>
	 * Mixin prepares every mod's config as the launch target starts, loading outer classes before
	 * any post-GAME mod hook exists. We beat that by running from an {@code EarlyServiceBridgePlugin}
	 * launch plugin injected into ModLauncher: its {@code initializeLaunch} fires during
	 * {@code announceLaunch}, after the GAME {@code TransformingClassLoader} is built but before Mixin's
	 * prep. Idempotent, claimed only once we actually hold the GAME classloader, so a too-early call
	 * does not poison the real one.
	 *
	 * <p>
	 * Bridges both classes ({@code parentLoaders}) and resources ({@code resolvedRoots}), so it
	 * works for a plain split early service (Sodium) AND for a coremod whose outer jar owns the mixins
	 * (Sinytra Connector) - Mixin reads mixin classes as bytecode resources. Only a
	 * <em>standalone</em> coremod (itself a mod, added to the GAME layer as its real self) is skipped.
	 */
	public static void bridgeEarlyServicesToGameLayer() {
		// A standalone coremod that is itself a mod (root neoforge.mods.toml) was added to the
		// GAME layer as its real self by EarlyModLocator, so its outer classes already resolve
		// there - bridging would redirect them to the child, whose loader does NOT transform
		// classes (mixins into that mod would silently stop applying). Everything else - plain
		// early services (Sodium) AND non-standalone coremods whose outer jar owns the mixins
		// (Sinytra Connector) - resolves through the bridge, with no game-library copy.
		ModLauncherEarlyServiceBridge.bridgeEarlyServicesToGameLayer(jar -> isCoremodJar(jar) && isStandaloneModFile(jar));
	}

	/**
	 * Whether this jar ships a coremod ({@code ICoreMod}). Such a jar (e.g. Sinytra Connector)
	 * runs its own mod discovery and its coremod transformers are collected by FML's
	 * {@code transformers()} pass before the GAME layer exists, so they are forwarded from the
	 * child SERVICE layer by {@link AutoModpackCoreMod}. A non-standalone coremod's outer classes
	 * (which its own mixins live in) reach the GAME layer through {@link
	 * #bridgeEarlyServicesToGameLayer()}, not a copy.
	 */
	public static boolean isCoremodJar(Path jar) {
		return info(jar).coremod();
	}

	public static boolean isEarlyServiceJar(Path jar) {
		return ModLauncherEarlyServiceBridge.isEarlyServiceJar(jar);
	}

	static ClassLoader classLoaderFor(Path jar) {
		return ModLauncherEarlyServiceBridge.classLoaderFor(jar);
	}

	static void register(Path jar, ClassLoader serviceClassLoader, ModuleLayer childLayer, String moduleName) {
		ModLauncherEarlyServiceBridge.register(jar, serviceClassLoader, childLayer, moduleName);
	}

	/** Reads the implementation class names listed in a {@code META-INF/services/...} file. */
}
