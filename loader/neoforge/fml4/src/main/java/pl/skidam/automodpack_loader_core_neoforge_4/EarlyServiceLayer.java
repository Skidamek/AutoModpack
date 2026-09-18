package pl.skidam.automodpack_loader_core_neoforge_4;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.coremod.ICoreMod;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;

import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.loader.ServiceJarIndex;
import pl.skidam.automodpack_loader_core_modlauncher.ModLauncherEarlyServiceBridge;
import pl.skidam.automodpack_loader_core_neoforge_shared.EarlyServiceReplay;

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
 * The ModLauncher-family machinery this shares with legacy Forge - the child-layer build, the
 * child-layer registry, the {@code ITransformationService} forwarding engine and the GAME-classloader
 * bridge - lives in {@link ModLauncherEarlyServiceBridge}; the locator/reader replay machinery it
 * shares with the flat-classloader fml10/11 generation lives in {@link EarlyServiceReplay}.
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
	// ImmediateWindowProvider is deliberately NOT here (though it IS in HANDLED_SERVICES): NeoForge picks
	// the early-window provider and creates the window in the same call, before and out of reach of
	// anything we can do from the active projection, so a mod needing it must force-copy to mods/.
	public static final Set<String> HANDLEABLE_SERVICES = Stream.concat(ACTIVELY_RUN_SERVICES.stream(), Stream.of(LANGUAGE_LOADER_SERVICE))
			.collect(Collectors.toUnmodifiableSet());

	// Every service the running loader version actually handles, read from the loader itself
	// (TransformerDiscovererConstants.SERVICES, plus ICoreMod/IModLanguageLoader which FML handles
	// outside that set) rather than a hand-maintained cross-version list. The force-copy decision
	// counts only services in here: one the loader doesn't handle can't be fixed by copying either.
	private static final Set<String> HANDLED_SERVICES = computeHandledServices();

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

	// Per-jar facts (services, replayed impls, standalone probe) come from the shared
	// ServiceJarIndex, scoped to HANDLED_SERVICES so a legacy/removed SPI (e.g. the old IModLocator)
	// can't make a jar look unhostable.
	private static ServiceJarIndex.Facts<Boolean> facts(Path jar) {
		return ServiceJarIndex.facts(jar, HANDLED_SERVICES, ACTIVELY_RUN_SERVICES, fs -> Files.exists(fs.getPath("META-INF/neoforge.mods.toml")));
	}

	/** The impl class names of an {@link #ACTIVELY_RUN_SERVICES} service declared at the jar's root. */
	public static List<String> serviceImpls(Path jar, String serviceFile) {
		return facts(jar).implsOf(serviceFile);
	}

	/**
	 * Runs the hosted jars' {@code IModFileCandidateLocator}s against AutoModpack's discovery pipeline.
	 * This is how mods like Sodium load their real (inner) mod jar - the loader would otherwise only
	 * run this from its SERVICE layer, which AutoModpack never reaches. The replay itself is shared
	 * with the fml10/11 generation (see {@link EarlyServiceReplay}); only this generation's
	 * service cache and child-layer classloaders are bound here.
	 */
	public static void runCandidateLocators(List<Path> jars, ILaunchContext context, IDiscoveryPipeline pipeline) {
		EarlyServiceReplay.runCandidateLocators(jars, jar -> serviceImpls(jar, CANDIDATE_LOCATOR_SERVICE), EarlyServiceLayer::classLoaderFor, context, pipeline);
	}

	/**
	 * Runs the hosted jars' {@code IDependencyLocator}s. This is how mods like Ixeris load their real
	 * (inner) mod jar. Shared with the fml10/11 generation (see {@link EarlyServiceReplay}).
	 */
	public static void runDependencyLocators(List<Path> jars, List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
		EarlyServiceReplay.runDependencyLocators(jars, jar -> serviceImpls(jar, DEPENDENCY_LOCATOR_SERVICE), EarlyServiceLayer::classLoaderFor, loadedMods, pipeline);
	}

	/**
	 * Forwards an early-service jar's {@code IModFileReader}s into the live discovery pipeline, so a
	 * modpack-folder mod that ships a reader for a custom mod-file format can interpret candidates in
	 * place - no copy needed. Instantiates this generation's readers from the jar's child SERVICE
	 * layer; the splice into the pipeline's reader list is shared with the fml10/11 generation (see
	 * {@link EarlyServiceReplay#forwardModFileReaders}).
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
		EarlyServiceReplay.forwardModFileReaders(readers, jar.getFileName().toString(), pipeline);
	}

	/**
	 * Whether this jar's early services can all be run from the active projection. It must declare at
	 * its root at least one service we actively run in place (a {@code GraphicsBootstrapper}, a
	 * candidate/dependency locator, an {@code IModFileReader}, or a coremod's {@code ICoreMod}) AND
	 * ship no service outside {@link #HANDLEABLE_SERVICES}. Anything else is left for the
	 * copy-to-standard path rather than being half-loaded in place.
	 */
	public static boolean eligibleForInPlace(Path jar) {
		ServiceJarIndex.Facts<Boolean> facts = facts(jar);
		// Must actively run at least one service in place (root), and ship nothing we can't host.
		return !facts.serviceImpls().isEmpty() && HANDLEABLE_SERVICES.containsAll(facts.services());
	}

	/**
	 * Whether the jar is itself a loadable mod - it declares a root {@code neoforge.mods.toml} - as
	 * opposed to a thin outer jar whose real mod is nested or absent (e.g. Sodium's split jar, or
	 * Sinytra Connector's locator jar). A coremod jar that IS a standalone mod is added to the GAME
	 * layer as its real self (loading normally) and so is left out of the GAME-layer bridge; a
	 * non-standalone one has its outer classes bridged there instead.
	 */
	public static boolean isStandaloneModFile(Path jar) {
		return Boolean.TRUE.equals(facts(jar).extra());
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
	 * AutoModpackTransformationService#transformers()}.)
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
			// AutoModpackTransformationService#transformers() - that service is itself natively
			// ServiceLoader-discovered the same way a real ITransformationService would be, so
			// ModLauncher calls its transformers() directly; forwarding them again here would register
			// every one of them twice.
		}
		return transformers;
	}

	/**
	 * Points the GAME classloader at each in-place early-service jar's child SERVICE layer (the
	 * mechanics live on {@link ModLauncherEarlyServiceBridge#bridgeEarlyServicesToGameLayer}). This
	 * generation's only own decision is the skip rule below.
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
		return !facts(jar).implsOf(COREMOD_SERVICE).isEmpty();
	}

	public static boolean isEarlyServiceJar(Path jar) {
		return ModLauncherEarlyServiceBridge.isEarlyServiceJar(jar);
	}

	/** The child SERVICE layer classloader of a hosted jar, or null if it never registered - the replay source this generation binds to. */
	static ClassLoader classLoaderFor(Path jar) {
		return ModLauncherEarlyServiceBridge.classLoaderFor(jar);
	}

	/**
	 * Resolves every eligible jar into ONE shared child configuration/layer/classloader (via {@link
	 * ModLauncherEarlyServiceBridge#buildChildLayers}), then fires the jars' own {@code
	 * GraphicsBootstrapper}s on it.
	 *
	 * <p>
	 * Lives here rather than on {@link EarlyServiceBootstrapper} so the bootstrapper stays free of
	 * ModLauncher/securejarhandler bytecode: the universal outer jar registers both generations'
	 * bootstrappers under the same services, ServiceLoader links every provider it instantiates, and
	 * those classes do not exist on the flat-classloader generation - linking them there would crash
	 * the launch. Only the fml4 generation ever executes into this class, so only it ever links it.
	 */
	static void bootstrapJars(List<Path> jars, ModuleLayer serviceLayer, String[] arguments) {
		List<Path> registered = ModLauncherEarlyServiceBridge.buildChildLayers(jars, serviceLayer, "FML Early Services");

		for (Path jar : registered) {
			for (String impl : serviceImpls(jar, GRAPHICS_BOOTSTRAPPER_SERVICE)) {
				try {
					GraphicsBootstrapper bootstrapper = (GraphicsBootstrapper) Class.forName(impl, true, classLoaderFor(jar)).getDeclaredConstructor().newInstance();
					LOGGER.debug("[AutoModpack] Invoking in-place GraphicsBootstrapper {} ({}) from {}", impl, bootstrapper.name(), jar.getFileName());
					bootstrapper.bootstrap(arguments);
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] In-place GraphicsBootstrapper {} from {} failed", impl, jar.getFileName(), t);
				}
			}
		}
	}
}
