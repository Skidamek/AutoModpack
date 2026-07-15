package pl.skidam.automodpack_loader_core_forge;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import net.minecraftforge.forgespi.locating.IModFile;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.utils.EarlyServiceScan;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_loader_core_modlauncher.EarlyServiceBridgePlugin;
import pl.skidam.automodpack_loader_core_modlauncher.ModuleClassLoaderAccess;

/**
 * Forge's analog of the NeoForge fml4 {@code EarlyServiceLayer}: both loaders run on the same
 * ModLauncher/securejarhandler module-layer machinery ({@code cpw.mods.*}), so the core mechanism -
 * a shared child SERVICE {@link ModuleLayer} for the modpack's early-service jars, bridged into the
 * GAME classloader - is unchanged. Forge has no pre-ModLauncher hook, so {@link #bootstrap} instead
 * runs from {@link AutoModpackTransformationService#onLoad}, the earliest point ModLauncher hands
 * control to AutoModpack. Forge's {@code ICoreMod} coremod system is not supported (rare, JS-based,
 * not a per-jar SPI).
 *
 * <p>
 * Handled services - {@code ITransformationService} (forwarded by
 * {@link AutoModpackTransformationService}), {@code IModLocator}, and {@code IDependencyLocator} -
 * let a mod shipping only these load straight from the modpack folder with no copy.
 */
public final class EarlyServiceLayer {

	private EarlyServiceLayer() {}

	public static final String CANDIDATE_LOCATOR_SERVICE = LoaderServicePaths.FORGE_MOD_LOCATOR;
	public static final String DEPENDENCY_LOCATOR_SERVICE = LoaderServicePaths.FORGE_DEPENDENCY_LOCATOR;
	public static final String LANGUAGE_LOADER_SERVICE = LoaderServicePaths.FORGE_LANGUAGE_PROVIDER;
	public static final String TRANSFORMATION_SERVICE = LoaderServicePaths.TRANSFORMATION_SERVICE;

	// Services requiring active work: candidate/dependency locators are replayed, and a
	// transformation service's lifecycle is forwarded (see AutoModpackTransformationService).
	static final List<String> ACTIVELY_RUN_SERVICES = List.of(CANDIDATE_LOCATOR_SERVICE, DEPENDENCY_LOCATOR_SERVICE, TRANSFORMATION_SERVICE);

	// Every service hostable from the modpack folder without copying: the actively-run ones above,
	// plus language providers (passive - picked up from the GAME layer).
	public static final Set<String> HANDLEABLE_SERVICES = Stream.concat(ACTIVELY_RUN_SERVICES.stream(), Stream.of(LANGUAGE_LOADER_SERVICE))
			.collect(Collectors.toUnmodifiableSet());

	// Services the running Forge's own ModDirTransformerDiscoverer scans for - a mods/-dir jar
	// shipping any of these is claimed for the SERVICE layer and excluded from mod discovery by
	// ModsFolderLocator, even with a root mods.toml. Read from the loader's own SERVICES field
	// (1.20.1+; 1.18.2 hardcodes the check instead, hence the fallback) so it's exact per version.
	private static final Set<String> NATIVE_EXCLUSION_SERVICES = computeNativeExclusionServices();

	// Every service this Forge version actually handles: natively-excluded set above, plus
	// IDependencyLocator (run post-discovery) and language providers.
	private static final Set<String> HANDLED_SERVICES = computeHandledServices();

	/**
	 * Service files this loader version actually discovers/runs; superset of {@link #HANDLEABLE_SERVICES}.
	 * Used to narrow a jar's raw service set (see {@link #inspect}) so a legacy/removed SPI this
	 * loader version doesn't handle can't wrongly block {@link #eligibleForInPlace}.
	 */
	private static Set<String> knownServices() {
		return HANDLED_SERVICES;
	}

	private static Set<String> computeNativeExclusionServices() {
		Set<String> excluded = new HashSet<>();
		try {
			Class<?> discoverer = Class.forName("net.minecraftforge.fml.loading.ModDirTransformerDiscoverer");
			Field field = discoverer.getDeclaredField("SERVICES");
			field.setAccessible(true);
			Object services = field.get(null);
			if (services instanceof Set<?> names) {
				for (Object name : names) {
					// SERVICES holds dotted class names; we compare full service-file paths elsewhere.
					excluded.add("META-INF/services/" + name);
				}
			}
		} catch (Throwable t) {
			LOGGER.warn("[AutoModpack] Could not read the loader's early-service list; using the built-in fallback", t);
			excluded.add(CANDIDATE_LOCATOR_SERVICE);
			excluded.add(TRANSFORMATION_SERVICE);
		}
		return Set.copyOf(excluded);
	}

	private static Set<String> computeHandledServices() {
		Set<String> handled = new HashSet<>(NATIVE_EXCLUSION_SERVICES);
		handled.add(DEPENDENCY_LOCATOR_SERVICE);
		handled.add(LANGUAGE_LOADER_SERVICE);
		return Set.copyOf(handled);
	}

	// classLoader/layer of a handled jar's child SERVICE layer, kept together since the GAME-layer
	// bridge needs both. moduleName scopes bridging to this jar's own module on the shared layer.
	private record JarService(ClassLoader classLoader, ModuleLayer layer, String moduleName) {}

	// Maps each handled modpack jar to its child-layer classloader/layer. Populated by bootstrap().
	private static final Map<Path, JarService> JAR_SERVICES = new ConcurrentHashMap<>();

	// ITransformationService instances instantiated in place per jar, kept so the injected forwarding
	// service (AutoModpackTransformationService) can run their completeScan at ModLauncher's native,
	// post-discovery time, when its returned resources still reach the GAME/PLUGIN layers.
	private static final Map<Path, List<ITransformationService>> TRANSFORMATION_SERVICES = new ConcurrentHashMap<>();

	// bootstrap() must run exactly once; EarlyModLocator#scanMods() can be invoked more than once.
	private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);

	// The GAME-layer bridge must run exactly once, before Mixin loads any outer class.
	private static final AtomicBoolean GAME_BRIDGE_DONE = new AtomicBoolean(false);

	// Every package routed from GAME's parentLoaders into a child SERVICE layer, across all bridged
	// jars. Shared (not per-jar) so a child's fallback wrapper (see NonReentrantGameFallback) refuses
	// ANY package we routed away from GAME, regardless of which child loader owns it - otherwise
	// GAME -> childA -> (fallback) -> GAME -> childB could still ping-pong.
	private static final Set<String> BRIDGED_PACKAGES = ConcurrentHashMap.newKeySet();

	/**
	 * Builds one shared child SERVICE layer for every eligible modpack-folder early-service jar,
	 * registers it, instantiates each jar's declared {@code ITransformationService}(s) (forwarded
	 * lazily by {@link AutoModpackTransformationService}), and replays each jar's candidate/dependency
	 * locators so their real (inner) mods are discovered. Idempotent - safe to call from every
	 * {@link EarlyModLocator#scanMods} invocation.
	 */
	public static void bootstrap() {
		if (!BOOTSTRAPPED.compareAndSet(false, true)) return;

		try {
			// selectedModpackDir is published by Preload (run just before this) and set only when a
			// modpack is selected on a client.
			Path modpackMods = Constants.selectedModpackDir == null ? null : Constants.selectedModpackDir.resolve("mods");
			if (modpackMods == null || !Files.isDirectory(modpackMods)) { return; }

			List<Path> earlyServiceJars = EarlyServiceScan.eligibleJars(modpackMods, EarlyServiceLayer::eligibleForInPlace);

			if (earlyServiceJars.isEmpty()) { return; }

			Constants.LOGGER.info("[AutoModpack] Bootstrapping {} early-service mod(s) from the modpack folder in place", earlyServiceJars.size());

			ModuleLayer serviceLayer = EarlyServiceLayer.class.getModule().getLayer();
			if (serviceLayer == null) {
				LOGGER.warn("[AutoModpack] Not running on a module layer, cannot bootstrap early services in place");
				return;
			}

			buildChildLayer(earlyServiceJars, serviceLayer);

			for (Path jar : earlyServiceJars) {
				for (String impl : serviceImpls(jar, TRANSFORMATION_SERVICE)) {
					try {
						ITransformationService service = (ITransformationService) Class.forName(impl, true, classLoaderFor(jar)).getDeclaredConstructor()
								.newInstance();
						TRANSFORMATION_SERVICES.computeIfAbsent(jar, k -> new ArrayList<>()).add(service);
						LOGGER.debug("[AutoModpack] Instantiated in-place transformation service {} ({})", impl, jar.getFileName());
					} catch (Throwable t) {
						LOGGER.error("[AutoModpack] Failed to instantiate in-place transformation service {} from {}", impl, jar.getFileName(), t);
					}
				}
			}

			EarlyServiceBridgePlugin.ensureRunsFirst(EarlyServiceLayer::bridgeEarlyServicesToGameLayer);
		} catch (Throwable t) {
			LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
		}
	}

	/**
	 * Resolves every eligible jar into ONE shared child configuration/layer/classloader, mirroring
	 * how the loader's own SERVICE layer resolves every jar destined for it together in a single
	 * {@code Configuration.resolveAndBind} call - letting an early-service jar split across, or
	 * dependent on, more than one modpack-folder jar resolve exactly as it would on the real layer.
	 */
	private static void buildChildLayer(List<Path> jars, ModuleLayer serviceLayer) {
		if (buildAndRegister(jars, serviceLayer)) return;
		// The shared resolution failed (e.g. two jars deriving the same automatic module name throw
		// a ResolutionException for the whole batch). Retry each jar on its own layer so one bad jar
		// doesn't take every other early-service mod down with it - cross-jar `requires` edges are
		// lost in this degraded mode, but only the jars that actually fail resolution stay out.
		for (Path jar : jars) {
			buildAndRegister(List.of(jar), serviceLayer);
		}
	}

	/**
	 * Resolves the given jars into one child configuration/layer/classloader and registers each in
	 * {@link #JAR_SERVICES}. Returns false - with nothing registered - if resolution fails.
	 */
	private static boolean buildAndRegister(List<Path> jars, ModuleLayer serviceLayer) {
		try {
			SecureJar[] secureJars = new SecureJar[jars.size()];
			List<String> moduleNames = new ArrayList<>(jars.size());
			for (int i = 0; i < jars.size(); i++) {
				SecureJar secureJar = SecureJar.from(jars.get(i));
				secureJars[i] = secureJar;
				moduleNames.add(secureJar.name());
			}

			Configuration configuration = serviceLayer.configuration().resolveAndBind(JarModuleFinder.of(secureJars), ModuleFinder.of(), moduleNames);

			List<ModuleLayer> parentLayers = flattenParents(serviceLayer);

			ModuleClassLoader classLoader = new ModuleClassLoader("AutoModpack Early Services", configuration, parentLayers);
			classLoader.setFallbackClassLoader(EarlyServiceLayer.class.getClassLoader());
			ModuleLayer childLayer = ModuleLayer.defineModules(configuration, List.of(serviceLayer), name -> classLoader).layer();

			for (int i = 0; i < jars.size(); i++) {
				JAR_SERVICES.put(canonical(jars.get(i)), new JarService(classLoader, childLayer, moduleNames.get(i)));
			}
			return true;
		} catch (Throwable t) {
			LOGGER.error("[AutoModpack] Could not build a service layer for early-service jar(s) {}", jars.stream().map(Path::getFileName).toList(), t);
			return false;
		}
	}

	private static List<ModuleLayer> flattenParents(ModuleLayer layer) {
		List<ModuleLayer> result = new ArrayList<>();
		Deque<ModuleLayer> queue = new ArrayDeque<>();
		queue.add(layer);
		while (!queue.isEmpty()) {
			ModuleLayer current = queue.poll();
			if (!result.contains(current)) {
				result.add(current);
				queue.addAll(current.parents());
			}
		}
		return result;
	}

	public static boolean isEarlyServiceJar(Path jar) {
		return jar != null && JAR_SERVICES.containsKey(canonical(jar));
	}

	private static ClassLoader classLoaderFor(Path jar) {
		if (jar == null) return null;
		JarService service = JAR_SERVICES.get(canonical(jar));
		return service == null ? null : service.classLoader();
	}

	// Purely lexical: writer and readers all list the same modpack mods/ folder, no symlinks
	// involved, so lexical equality already holds.
	private static Path canonical(Path jar) {
		return jar.toAbsolutePath().normalize();
	}

	// Per-jar facts derived from a single jar mount, cached for the JVM's life.
	private record JarInfo(boolean activelyRunInPlace, Set<String> services, Map<String, List<String>> serviceImpls) {}

	private static final Map<Path, JarInfo> JAR_INFO = new ConcurrentHashMap<>();

	private static JarInfo info(Path jar) {
		return JAR_INFO.computeIfAbsent(canonical(jar), EarlyServiceLayer::inspect);
	}

	private static JarInfo inspect(Path jar) {
		boolean activelyRun = false;
		Set<String> services = Set.of();
		Map<String, List<String>> impls = new HashMap<>();
		try (FileSystem fs = FileSystems.newFileSystem(jar)) {
			// Scoped to what this loader version actually handles (knownServices()), so a legacy/
			// removed SPI doesn't wrongly make an otherwise in-place-able mod look unhandleable.
			services = FileInspection.getServices(fs, knownServices());
			for (String service : ACTIVELY_RUN_SERVICES) {
				if (Files.exists(fs.getPath(service))) {
					activelyRun = true;
					impls.put(service, readServiceImpls(fs, service));
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[AutoModpack] Could not inspect {}; not handling it in place", jar.getFileName(), e);
		}
		return new JarInfo(activelyRun, services, impls);
	}

	/** The impl class names of an {@link #ACTIVELY_RUN_SERVICES} service declared at the jar's root. */
	public static List<String> serviceImpls(Path jar, String serviceFile) {
		return info(jar).serviceImpls().getOrDefault(serviceFile, List.of());
	}

	/**
	 * Whether this jar's early services can all be run from the modpack folder: it must declare at
	 * least one actively-run service, ship no service outside {@link #HANDLEABLE_SERVICES}, and be a
	 * jar native Forge would itself exclude from mod discovery for the SERVICE layer. The last part is
	 * what makes in-place treatment safe - such a jar never loads as a plain mod natively, so replaying
	 * its locators can't double-load or shadow anything (e.g. CrashAssistant's outer/inner jars share
	 * one modId; loading both natively would be a duplicate-mod crash).
	 */
	public static boolean eligibleForInPlace(Path jar) {
		JarInfo info = info(jar);
		return info.activelyRunInPlace() && HANDLEABLE_SERVICES.containsAll(info.services()) && nativelyServiceClaimed(info);
	}

	/** Whether native Forge would claim this jar for the SERVICE layer (see {@link #eligibleForInPlace}). */
	private static boolean nativelyServiceClaimed(JarInfo info) {
		for (String service : info.serviceImpls().keySet()) {
			if (NATIVE_EXCLUSION_SERVICES.contains(service)) return true;
		}
		return false;
	}

	/**
	 * Runs the {@code IModLocator}s declared inside an early-service jar, merging their results into
	 * {@code out} - how a modpack-folder split-jar mod loads its real (inner) mod jar.
	 *
	 * <p>
	 * Invoked by reflection, not a static cast: {@code scanMods()}'s return type differs between
	 * Forge 1.18.2 and ~1.19+ ({@code ModFileOrException} doesn't exist pre-1.19), and this module
	 * compiles once against a single forgespi version for both.
	 */
	public static void runCandidateLocators(Path jar, List<Object> out) {
		ClassLoader cl = classLoaderFor(jar);
		if (cl == null) return;
		for (String impl : serviceImpls(jar, CANDIDATE_LOCATOR_SERVICE)) {
			try {
				Object locator = Class.forName(impl, true, cl).getDeclaredConstructor().newInstance();
				LOGGER.debug("[AutoModpack] Running in-place candidate locator {} from {}", impl, jar.getFileName());
				List<?> result = (List<?>) locator.getClass().getMethod("scanMods").invoke(locator);
				out.addAll(result);
			} catch (Throwable t) {
				LOGGER.error("[AutoModpack] Failed to run candidate locator {} from {}", impl, jar.getFileName(), t);
			}
		}
	}

	/**
	 * Runs an early-service jar's post-discovery dependency locators, merging their results into
	 * {@code out} - how a modpack-folder split-jar mod pulls in its real (inner) mod jar once the
	 * base mod set is known. Two forgespi shapes are handled, both reflectively, so this module
	 * compiles once against a single version:
	 * <ul>
	 * <li>Forge ~1.19+: a standalone {@code IDependencyLocator} with {@code scanMods(Iterable)}.</li>
	 * <li>Forge 1.18.2: no standalone {@code IDependencyLocator} exists - a dependency locator is
	 * declared under {@code IModLocator} and carries its logic in {@code scanMods(Iterable)}
	 * (its no-arg {@code scanMods()}, run in the candidate phase, finds nothing). We replay
	 * that hook here too. On ~1.19+ {@code IModLocator} has no {@code scanMods(Iterable)}, so
	 * the lookup misses and this second pass is a no-op.</li>
	 * </ul>
	 */
	public static void runDependencyLocators(Path jar, Iterable<IModFile> loadedMods, List<IModFile> out) {
		ClassLoader cl = classLoaderFor(jar);
		if (cl == null) return;
		Set<String> handled = new HashSet<>();
		for (String impl : serviceImpls(jar, DEPENDENCY_LOCATOR_SERVICE)) {
			if (runScanModsIterable(cl, impl, jar, loadedMods, out)) handled.add(impl);
		}
		// Forge 1.18.2: dependency locators live under IModLocator (see method doc). Skipped on
		// ~1.19+, where these impls have no scanMods(Iterable), and deduped against the pass above.
		for (String impl : serviceImpls(jar, CANDIDATE_LOCATOR_SERVICE)) {
			if (!handled.contains(impl)) runScanModsIterable(cl, impl, jar, loadedMods, out);
		}
	}

	/**
	 * Instantiates {@code impl} and invokes its {@code scanMods(Iterable)} dependency hook, appending
	 * any located mod files to {@code out}. Returns false, leaving {@code out} untouched, when the
	 * class exposes no such method - the discriminator that keeps a plain candidate {@code
	 * IModLocator} (Forge ~1.19+, no {@code scanMods(Iterable)}) out of the dependency pass.
	 */
	private static boolean runScanModsIterable(ClassLoader cl, String impl, Path jar, Iterable<IModFile> loadedMods, List<IModFile> out) {
		Object locator;
		Method scanMods;
		try {
			locator = Class.forName(impl, true, cl).getDeclaredConstructor().newInstance();
			scanMods = locator.getClass().getMethod("scanMods", Iterable.class);
		} catch (NoSuchMethodException e) {
			return false; // a candidate-only IModLocator: nothing to run in the dependency phase
		} catch (Throwable t) {
			LOGGER.error("[AutoModpack] Failed to run dependency locator {} from {}", impl, jar.getFileName(), t);
			return false;
		}
		try {
			LOGGER.debug("[AutoModpack] Running in-place dependency locator {} from {}", impl, jar.getFileName());
			@SuppressWarnings("unchecked")
			List<IModFile> result = (List<IModFile>) scanMods.invoke(locator, loadedMods);
			if (result != null) out.addAll(result);
			return true;
		} catch (Throwable t) {
			LOGGER.error("[AutoModpack] Failed to run dependency locator {} from {}", impl, jar.getFileName(), t);
			return false;
		}
	}

	/** Every currently-registered early-service jar path (for replay loops in the locators). */
	public static Set<Path> registeredJars() {
		return Set.copyOf(JAR_SERVICES.keySet());
	}

	private interface ServiceAction {
		void run(ITransformationService service) throws Throwable;
	}

	private interface ServiceQuery<R> {
		List<R> run(ITransformationService service) throws Throwable;
	}

	private static void forEachTransformationService(String verb, ServiceAction action) {
		for (Map.Entry<Path, List<ITransformationService>> entry : TRANSFORMATION_SERVICES.entrySet()) {
			for (ITransformationService service : entry.getValue()) {
				try {
					action.run(service);
					LOGGER.debug("[AutoModpack] Ran in-place transformation-service {} for {} ({})", verb, service.getClass().getName(),
							entry.getKey().getFileName());
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] In-place transformation service {} {} failed ({})", service.getClass().getName(), verb,
							entry.getKey().getFileName(), t);
				}
			}
		}
	}

	private static <R> List<R> collectFromTransformationServices(String verb, ServiceQuery<R> query) {
		List<R> results = new ArrayList<>();
		for (Map.Entry<Path, List<ITransformationService>> entry : TRANSFORMATION_SERVICES.entrySet()) {
			for (ITransformationService service : entry.getValue()) {
				try {
					List<R> produced = query.run(service);
					if (produced != null && !produced.isEmpty()) {
						results.addAll(produced);
						LOGGER.debug("[AutoModpack] Forwarded {} {} result(s) from in-place transformation service {} ({})", produced.size(), verb,
								service.getClass().getName(), entry.getKey().getFileName());
					}
				} catch (Throwable t) {
					LOGGER.error("[AutoModpack] In-place transformation service {} {} failed ({})", service.getClass().getName(), verb,
							entry.getKey().getFileName(), t);
				}
			}
		}
		return results;
	}

	static void forwardOnLoad(IEnvironment env, Set<String> otherServices) {
		forEachTransformationService("onLoad", service -> service.onLoad(env, otherServices));
	}

	static void forwardInitialize(IEnvironment environment) {
		forEachTransformationService("initialize", service -> service.initialize(environment));
	}

	static List<ITransformationService.Resource> forwardBeginScanning(IEnvironment environment) {
		return collectFromTransformationServices("beginScanning", service -> service.beginScanning(environment));
	}

	static List<ITransformationService.Resource> forwardCompleteScan(IModuleLayerManager layerManager) {
		return collectFromTransformationServices("completeScan", service -> service.completeScan(layerManager));
	}

	static List<ITransformer> collectTransformationServiceTransformers() {
		return collectFromTransformationServices("transformers", service -> new ArrayList<>(service.transformers()));
	}

	/**
	 * Points the GAME classloader at each in-place early-service jar's child SERVICE layer, wiring
	 * {@code cpw.mods.cl} parentLoaders/resolvedRoots and JPMS module reads so GAME-layer code (e.g.
	 * Mixin) can see and load the early-service jar's outer classes/resources.
	 */
	public static void bridgeEarlyServicesToGameLayer() {
		if (GAME_BRIDGE_DONE.get()) return;

		ClassLoader gameClassLoader = resolveGameClassLoader();
		if (!(gameClassLoader instanceof ModuleClassLoader)) { return; }

		if (!GAME_BRIDGE_DONE.compareAndSet(false, true)) return;

		Map<String, ClassLoader> gameParentLoaders = ModuleClassLoaderAccess.parentLoaders(gameClassLoader);
		Map<String, Object> gamePackageLookup = ModuleClassLoaderAccess.packageLookup(gameClassLoader);
		Map<String, Object> gameResolvedRoots = ModuleClassLoaderAccess.resolvedRoots(gameClassLoader);
		ModuleLayer gameLayer = gameLayerOrNull();

		for (Map.Entry<Path, JarService> entry : JAR_SERVICES.entrySet()) {
			Path jar = entry.getKey();
			ClassLoader childLoader = entry.getValue().classLoader();
			// Every registered jar is one native Forge would exclude from mod discovery (see
			// eligibleForInPlace), so none of them has a GAME-layer twin to shadow - bridge them all.
			if (!(childLoader instanceof ModuleClassLoader)) continue;

			try {
				// All jars share ONE child loader/layer (see buildChildLayer), so scope the bridge
				// to THIS jar's own module - iterating the loader's whole packageLookup would also
				// bridge the packages of jars skipped above.
				Module module = entry.getValue().layer().findModule(entry.getValue().moduleName()).orElse(null);
				if (module == null) continue;
				Set<String> childServable = ModuleClassLoaderAccess.packageLookup(childLoader).keySet();
				int bridged = 0;
				int skipped = 0;
				int nativeRouted = 0;
				int unservable = 0;
				for (String pkg : module.getPackages()) {
					// A package GAME's packageLookup already owns is a real GAME module (e.g. an
					// inner mod jar added by its own IDependencyLocator) - packageLookup has
					// precedence over parentLoaders in loadClass, so leave it alone. Stealing it here
					// would make GAME route the package to us while the inner class actually lives on
					// GAME, and our fallback would bounce right back (StackOverflowError).
					if (gamePackageLookup.containsKey(pkg)) {
						skipped++;
						continue;
					}
					// GAME's parentLoaders may already route this package natively (an
					// ancestor-layer module owns it - e.g. a duplicate installation of the same
					// mod sitting in standard mods/, claimed by the SERVICE layer). Native
					// resolution predates us and works; the bridge only ever ADDS routes.
					if (gameParentLoaders.containsKey(pkg)) {
						nativeRouted++;
						continue;
					}
					// The child's live packageLookup is what its loadClass consults; a package it
					// doesn't claim can never be served by it, so routing it there is a dead end.
					if (!childServable.contains(pkg)) {
						unservable++;
						continue;
					}
					// Route the outer package to the child (single class identity).
					gameParentLoaders.put(pkg, childLoader);
					BRIDGED_PACKAGES.add(pkg);
					bridged++;
				}
				Object root = ModuleClassLoaderAccess.resolvedRoots(childLoader).get(entry.getValue().moduleName());
				if (root != null) gameResolvedRoots.put(entry.getValue().moduleName(), root);
				// A class in a package WE routed to some child never bounces back into GAME (see
				// NonReentrantGameFallback).
				((ModuleClassLoader) childLoader).setFallbackClassLoader(new NonReentrantGameFallback(gameClassLoader, childLoader, BRIDGED_PACKAGES));
				LOGGER.debug("[AutoModpack] Bridged {} outer package(s) of {} to its early-service layer for in-place class/resource sharing{}", bridged,
						jar.getFileName(), skipped > 0 ? " (" + skipped + " shared package(s) left to the GAME layer)" : "");
				logBridgeDiagnostics(jar, entry.getValue().moduleName(), childLoader, module, bridged, skipped, nativeRouted, unservable);
				linkModuleReads(gameLayer, entry.getValue().layer(), jar);
			} catch (Throwable t) {
				LOGGER.error("[AutoModpack] Failed to bridge {} to the GAME layer", jar.getFileName(), t);
			}
		}
	}

	/**
	 * One-pass bridge-time verification: {@code module.getPackages()} (what we just routed) and the
	 * child loader's LIVE {@code packageLookup} (what its {@code loadClass} will actually consult)
	 * are built from the same {@code Configuration}, so a "ghost" package (in the descriptor but
	 * not the live map) should be statically impossible per securejarhandler's constructor. Ghosts
	 * are never routed (see the loop guards); this logs them to pin any live divergence.
	 */
	private static void logBridgeDiagnostics(Path jar, String moduleName, ClassLoader childLoader, Module module, int bridged, int skipped, int nativeRouted,
			int unservable) {
		try {
			Set<String> ghosts = new HashSet<>(module.getPackages());
			ghosts.removeAll(ModuleClassLoaderAccess.packageLookup(childLoader).keySet());
			LOGGER.debug(
					"[AutoModpack] Bridge diag: jar={} module={} childLoader=0x{} routed={} skippedGameOwned={} skippedNativeRoute={} skippedUnservable={} ghost={}",
					jar.getFileName(), moduleName, Integer.toHexString(System.identityHashCode(childLoader)), bridged, skipped, nativeRouted, unservable,
					ghosts.size());
			if (!ghosts.isEmpty()) {
				// Packages present in the module descriptor but absent from the child loader's
				// construction-time packageLookup - a securejarhandler lazy-metadata scan-timing quirk
				// seen only with automatic modules under live multi-module resolution. Benign: the
				// additive bridge never touches unrouted packages, so their native resolution is
				// untouched and the classes still load. DEBUG only, so it does not spam production.
				LOGGER.debug(
						"[AutoModpack] Bridge diag: {} ghost package(s) of {} left unrouted (in descriptor, absent from live packageLookup; served natively). First 10: {}",
						ghosts.size(), jar.getFileName(), ghosts.stream().sorted().limit(10).collect(Collectors.toList()));
			}
		} catch (Throwable t) {
			LOGGER.warn("[AutoModpack] Bridge diag failed for {}", jar.getFileName(), t);
		}
	}

	private static void linkModuleReads(ModuleLayer gameLayer, ModuleLayer childLayer, Path jar) {
		if (gameLayer == null || childLayer == null) return;
		Set<Module> gameModules = gameLayer.modules();
		Set<Module> childModules = childLayer.modules();
		for (Module child : childModules) {
			for (Module game : gameModules) {
				ModuleClassLoaderAccess.addReads(game, child);
				ModuleClassLoaderAccess.addReads(child, game);
			}
		}
		LOGGER.debug("[AutoModpack] Linked JPMS module reads for {}: {} child module(s) <-> {} game module(s)", jar.getFileName(), childModules.size(),
				gameModules.size());
	}

	/**
	 * The GAME {@code ModuleLayer} at the bridge (announceLaunch) phase, reached via ModLauncher's own
	 * {@code IModuleLayerManager} - {@code FMLLoader.getGameLayer()} is still null this early.
	 */
	private static ModuleLayer gameLayerOrNull() {
		try {
			Object launcher = ModuleClassLoaderAccess.launcherInstance();
			Object managerOpt = launcher.getClass().getMethod("findLayerManager").invoke(launcher);
			@SuppressWarnings("unchecked")
			IModuleLayerManager manager = ((Optional<IModuleLayerManager>) managerOpt).orElse(null);
			if (manager == null) return null;
			return manager.getLayer(IModuleLayerManager.Layer.GAME).orElse(null);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static ClassLoader resolveGameClassLoader() {
		ClassLoader ctx = Thread.currentThread().getContextClassLoader();
		if (ctx instanceof ModuleClassLoader) return ctx;
		ModuleLayer gameLayer = gameLayerOrNull();
		if (gameLayer != null) {
			for (Module module : gameLayer.modules()) {
				ClassLoader cl = module.getClassLoader();
				if (cl instanceof ModuleClassLoader) return cl;
			}
		}
		return null;
	}

	/**
	 * A child SERVICE layer's fallback classloader (replaces GAME itself as the direct fallback).
	 * Delegates to GAME normally, but refuses outright (no delegation) a class whose package is one
	 * WE routed into {@code gameParentLoaders} ({@link #BRIDGED_PACKAGES}): that package's authority
	 * is a child loader, which already had first crack at the class and fell through here BECAUSE it
	 * failed - GAME would only route the same package straight back to a child (itself or another
	 * sharing this same set), so bouncing there again can only recurse.
	 *
	 * <p>
	 * Resources are unaffected: {@code ModuleClassLoader.findResourceList} never consults {@code
	 * parentLoaders} or the fallback loader, only {@code resolvedRoots}, so {@link #getResource} and
	 * {@link #getResources} delegate to GAME unconditionally.
	 */
	private static final class NonReentrantGameFallback extends ClassLoader {
		private final ClassLoader gameClassLoader;
		private final ClassLoader childLoader; // the loader whose fallback this wrapper is (diagnostics only)
		private final Set<String> bridgedPackages;
		// Packages already reported by the refusal diagnostics (rate limit: one WARN per package).
		private final Set<String> reportedPackages = ConcurrentHashMap.newKeySet();

		NonReentrantGameFallback(ClassLoader gameClassLoader, ClassLoader childLoader, Set<String> bridgedPackages) {
			super(null); // only the two delegation paths below matter; no JDK platform-loader parent needed
			this.gameClassLoader = gameClassLoader;
			this.childLoader = childLoader;
			this.bridgedPackages = bridgedPackages;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			int lastDot = name.lastIndexOf('.');
			String pkg = lastDot < 0 ? "" : name.substring(0, lastDot);
			if (bridgedPackages.contains(pkg)) {
				logRefusal(name, pkg);
				throw new ClassNotFoundException(name); // a child was already the authority for this package
			}
			return gameClassLoader.loadClass(name);
		}

		/**
		 * A refusal here means the child that owns this bridged package could not serve the class -
		 * normally impossible for a class that exists in the jar. Log (once per package) the three
		 * facts that discriminate the possible causes: whether OUR child's live {@code packageLookup}
		 * claims the package at this moment (false = the map diverged from bridge time), and which
		 * loader GAME's {@code parentLoaders} currently routes the package to (not our child = an
		 * identity mismatch; the class fell through the wrong child's fallback).
		 */
		private void logRefusal(String name, String pkg) {
			if (!reportedPackages.add(pkg)) return;
			try {
				boolean childOwnsPkg = ModuleClassLoaderAccess.packageLookup(childLoader).containsKey(pkg);
				ClassLoader routed = ModuleClassLoaderAccess.parentLoaders(gameClassLoader).get(pkg);
				LOGGER.warn(
						"[AutoModpack] Fallback refused {} (package {}): wrapper's child=0x{}, child packageLookup containsKey(pkg)={}, GAME parentLoaders maps pkg to {}",
						name, pkg, Integer.toHexString(System.identityHashCode(childLoader)), childOwnsPkg,
						routed == null ? "null" : "0x" + Integer.toHexString(System.identityHashCode(routed)));
			} catch (Throwable t) {
				LOGGER.warn("[AutoModpack] Fallback refused {} (package {}); diagnostics failed", name, pkg, t);
			}
		}

		@Override
		public URL getResource(String name) {
			return gameClassLoader.getResource(name);
		}

		@Override
		public Enumeration<URL> getResources(String name) throws IOException {
			return gameClassLoader.getResources(name);
		}
	}

	private static List<String> readServiceImpls(FileSystem fs, String serviceFile) {
		List<String> impls = new ArrayList<>();
		Path service = fs.getPath(serviceFile);
		if (!Files.exists(service)) return impls;
		try (InputStream is = Files.newInputStream(service); BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int comment = line.indexOf('#');
				if (comment >= 0) line = line.substring(0, comment);
				line = line.trim();
				if (!line.isEmpty()) impls.add(line);
			}
		} catch (Exception e) {
			LOGGER.error("[AutoModpack] Failed to read {}", serviceFile, e);
		}
		return impls;
	}
}
