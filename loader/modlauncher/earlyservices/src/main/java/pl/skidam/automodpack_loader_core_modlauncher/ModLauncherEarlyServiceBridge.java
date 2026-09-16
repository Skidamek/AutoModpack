package pl.skidam.automodpack_loader_core_modlauncher;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;

/**
 * The ModLauncher-family half of both loaders' {@code EarlyServiceLayer} (legacy Forge and NeoForge
 * fml4): both run the same ModLauncher/securejarhandler module-layer machinery ({@code cpw.mods.*}),
 * so the GAME-classloader bridge mechanics, the in-place {@code ITransformationService} forwarding
 * engine and the child-layer registry are identical and live here once. Only the SPI-specific parts
 * (per-layer service-name constants, jar inspection/eligibility rules, locator replay) stay in each
 * loader's own {@code EarlyServiceLayer}, which delegates here.
 *
 * <p>
 * Compiled against legacy Forge's republished ModLauncher and bundled into both jars (relocated
 * alongside {@link ModuleClassLoaderAccess}): every {@code cpw.mods.*} type referenced here is
 * present with identical erasure in both runtime families (modlauncher 9/10 for Forge, 11 for
 * NeoForge fml4).
 */
public final class ModLauncherEarlyServiceBridge {

	private ModLauncherEarlyServiceBridge() {}

	// A handled modpack jar's child SERVICE layer classloader/layer, plus its own module name on
	// that (shared) layer, so the GAME-layer bridge can scope its work to this jar alone.
	private record JarService(ClassLoader classLoader, ModuleLayer layer, String moduleName) {}

	// Maps each handled modpack jar to its child-layer classloader/layer. Populated by each loader's
	// bootstrapper (NeoForge fml4's EarlyServiceBootstrapper, Forge's bootstrap()).
	private static final Map<Path, JarService> JAR_SERVICES = new ConcurrentHashMap<>();

	// ITransformationService instances instantiated in place per jar, kept so the forwarding service
	// injected into ModLauncher (each loader's AutoModpackTransformationService) can run their
	// completeScan at the native, post-discovery time - when its returned resources still reach the
	// GAME/PLUGIN layers.
	private static final Map<Path, List<ITransformationService>> TRANSFORMATION_SERVICES = new ConcurrentHashMap<>();

	// The GAME-layer bridge must run exactly once, from the injected launch plugin's
	// initializeLaunch (see EarlyServiceBridgePlugin), before Mixin loads any outer class.
	private static final AtomicBoolean GAME_BRIDGE_DONE = new AtomicBoolean(false);

	// Every package routed from GAME's parentLoaders into a child SERVICE layer, across all bridged
	// jars. Shared (not per-jar) so a child's fallback wrapper (see NonReentrantGameFallback) refuses
	// ANY package we routed away from GAME, regardless of which child loader owns it - otherwise
	// GAME -> childA -> (fallback) -> GAME -> childB could still ping-pong.
	private static final Set<String> BRIDGED_PACKAGES = ConcurrentHashMap.newKeySet();

	/** Registers a handled jar's child SERVICE layer classloader/layer and its own module name on it. */
	public static void register(Path jar, ClassLoader serviceClassLoader, ModuleLayer childLayer, String moduleName) {
		JAR_SERVICES.put(canonical(jar), new JarService(serviceClassLoader, childLayer, moduleName));
	}

	public static boolean isEarlyServiceJar(Path jar) {
		return jar != null && JAR_SERVICES.containsKey(canonical(jar));
	}

	public static ClassLoader classLoaderFor(Path jar) {
		if (jar == null) return null;
		JarService service = JAR_SERVICES.get(canonical(jar));
		return service == null ? null : service.classLoader();
	}

	/** Every currently-registered early-service jar path (for replay loops in the locators). */
	public static Set<Path> registeredJars() {
		return Set.copyOf(JAR_SERVICES.keySet());
	}

	/** Runs {@code consumer} against every registered jar and its child-layer classloader. */
	public static void forEachRegistered(BiConsumer<Path, ClassLoader> consumer) {
		for (Map.Entry<Path, JarService> entry : JAR_SERVICES.entrySet()) {
			consumer.accept(entry.getKey(), entry.getValue().classLoader());
		}
	}

	// Stable key so a jar matches whether reached via a relative or absolute path. Purely lexical
	// (no toRealPath()): writer and readers all derive paths from listing the same modpack mods/
	// folder, so lexical equality already holds and a real filesystem stat is avoided.
	public static Path canonical(Path jar) {
		return jar.toAbsolutePath().normalize();
	}

	/** Keeps an in-place jar's freshly instantiated {@code ITransformationService} for later forwarding. */
	public static void addTransformationService(Path jar, ITransformationService service) {
		TRANSFORMATION_SERVICES.computeIfAbsent(jar, k -> new ArrayList<>()).add(service);
	}

	public interface ServiceAction {
		void run(ITransformationService service) throws Throwable;
	}

	public interface ServiceQuery<R> {
		List<R> run(ITransformationService service) throws Throwable;
	}

	/**
	 * Runs {@code action} against every in-place transformation service, isolated in its own try/catch
	 * - one misbehaving in-place service (e.g. throwing {@code IncompatibleEnvironmentException} from
	 * {@code onLoad}) must not mark OUR service invalid and abort ModLauncher's {@code
	 * validateTransformationServices} for everyone.
	 */
	public static void forEachTransformationService(String verb, ServiceAction action) {
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

	/** Same isolation as {@link #forEachTransformationService}, collecting and logging each non-empty result. */
	public static <R> List<R> collectFromTransformationServices(String verb, ServiceQuery<R> query) {
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

	/**
	 * Points the GAME classloader at each in-place early-service jar's child SERVICE layer - the
	 * layer its services were actually bootstrapped on - so the outer classes resolve there, in
	 * place, with no GAME-library copy. Jars for which {@code skipJar} yields true are left alone.
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
	 * any post-GAME mod hook exists. We beat that by running from an {@link EarlyServiceBridgePlugin}
	 * launch plugin injected into ModLauncher: its {@code initializeLaunch} fires during
	 * {@code announceLaunch}, after the GAME {@code TransformingClassLoader} is built but before Mixin's
	 * prep. Idempotent via {@link #GAME_BRIDGE_DONE}, claimed only once we actually hold the GAME
	 * classloader, so a too-early call does not poison the real one.
	 *
	 * <p>
	 * Bridges both classes ({@code parentLoaders}) and resources ({@code resolvedRoots}), so it
	 * works for a plain split early service (Sodium) AND for a coremod whose outer jar owns the mixins
	 * (Sinytra Connector) - Mixin reads mixin classes as bytecode resources. Each loader's {@code
	 * EarlyServiceLayer} decides via {@code skipJar} which jars must not be bridged (NeoForge fml4
	 * skips a <em>standalone</em> coremod - itself a mod, added to the GAME layer as its real self;
	 * legacy Forge registers only jars natively excluded from mod discovery and skips none).
	 */
	public static void bridgeEarlyServicesToGameLayer(Predicate<Path> skipJar) {
		if (GAME_BRIDGE_DONE.get()) return;

		ClassLoader gameClassLoader = resolveGameClassLoader();
		if (!(gameClassLoader instanceof ModuleClassLoader)) {
			// The GAME TransformingClassLoader is not in scope yet (e.g. a launch plugin's
			// addResources runs before it is built). Our launch plugin's initializeLaunch retries
			// later, so do NOT consume the one-shot flag.
			return;
		}

		// We hold the real GAME classloader; claim the bridge exactly once.
		if (!GAME_BRIDGE_DONE.compareAndSet(false, true)) return;

		Map<String, ClassLoader> gameParentLoaders = ModuleClassLoaderAccess.parentLoaders(gameClassLoader);
		Map<String, Object> gamePackageLookup = ModuleClassLoaderAccess.packageLookup(gameClassLoader);
		Map<String, Object> gameResolvedRoots = ModuleClassLoaderAccess.resolvedRoots(gameClassLoader);
		// The GAME module layer, needed to wire JPMS read edges (see linkModuleReads). Resolved from
		// ModLauncher's layer manager, not FMLLoader.getGameLayer() - see gameLayerOrNull; null only
		// if the layer manager is unreachable, in which case reads-linking is skipped.
		ModuleLayer gameLayer = gameLayerOrNull();

		for (Map.Entry<Path, JarService> entry : JAR_SERVICES.entrySet()) {
			Path jar = entry.getKey();
			ClassLoader childLoader = entry.getValue().classLoader();
			// A caller-rejected jar is left alone (see the per-loader EarlyServiceLayer for whose
			// jars are skipped and why), as is anything without a ModuleClassLoader child.
			if (skipJar.test(jar) || !(childLoader instanceof ModuleClassLoader)) continue;

			try {
				// All jars share ONE child loader/layer (see the per-loader bootstrapper), so scope
				// the bridge to THIS jar's own module - iterating the loader's whole packageLookup
				// would also bridge the packages of jars skipped above.
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
					// GAME, and our fallback would bounce right back (StackOverflowError - seen live
					// with Ixeris' outer bootstrapper vs. its inner ...-mod.jar).
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
				// Resources: findResourceList never consults parentLoaders, only the loader's own
				// resolvedRoots, so add this jar's reference too - otherwise Mixin can't read an
				// outer-jar mixin class's bytecode (a resource) and config prep crashes.
				Object root = ModuleClassLoaderAccess.resolvedRoots(childLoader).get(entry.getValue().moduleName());
				if (root != null) gameResolvedRoots.put(entry.getValue().moduleName(), root);
				// The child layer's parents are only [SERVICE, BOOT] (built before GAME existed), so
				// it can't see minecraft/neoforge; point its fallback at a wrapper around GAME now
				// that GAME exists, so an outer class referencing net.minecraft.* resolves - but a
				// class in a package WE routed to some child never bounces back into GAME (see
				// NonReentrantGameFallback).
				((ModuleClassLoader) childLoader).setFallbackClassLoader(new NonReentrantGameFallback(gameClassLoader, childLoader, BRIDGED_PACKAGES));
				LOGGER.debug("[AutoModpack] Bridged {} outer package(s) of {} to its early-service layer for in-place class/resource sharing{}", bridged,
						jar.getFileName(), skipped > 0 ? " (" + skipped + " shared package(s) left to the GAME layer)" : "");
				logBridgeDiagnostics(jar, entry.getValue().moduleName(), childLoader, module, bridged, skipped, nativeRouted, unservable);
				// Classloader routing makes the outer classes loadable; JPMS still checks module
				// readability at access time, so wire the read edges a real ancestor layer would give.
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
				// seen only with automatic modules (e.g. Ixeris/asynclogger) under live multi-module
				// resolution. Benign: the additive bridge never touches unrouted packages, so their
				// native resolution is untouched and the classes still load (verified in prod). DEBUG
				// only, so it does not spam production every boot.
				LOGGER.debug(
						"[AutoModpack] Bridge diag: {} ghost package(s) of {} left unrouted (in descriptor, absent from live packageLookup; served natively). First 10: {}",
						ghosts.size(), jar.getFileName(), ghosts.stream().sorted().limit(10).collect(Collectors.toList()));
			}
		} catch (Throwable t) {
			LOGGER.warn("[AutoModpack] Bridge diag failed for {}", jar.getFileName(), t);
		}
	}

	/**
	 * Wires JPMS read edges both ways between the GAME layer and a bridged jar's child SERVICE layer:
	 * every GAME module reads every child module (so an inner module can access classes from the
	 * outer service module) and vice versa (so an outer class can access {@code net.minecraft.*}).
	 *
	 * <p>
	 * Natively these edges form across the SERVICE-&gt;GAME parent boundary via {@code requires}
	 * resolution. Our child layer is only a <em>sibling</em> of GAME, so no edge forms and the inner
	 * mod fails with {@code IllegalAccessError} once {@code ImmediateWindowHandler.acceptGameLayer}
	 * runs {@code updateModuleReads} - i.e. on any client launch. Edges are added broadly rather than
	 * by {@code requires} because the inner module is often automatic (no {@code requires} to key
	 * off); a read edge only grants access, so this cannot break anything.
	 */
	private static void linkModuleReads(ModuleLayer gameLayer, ModuleLayer childLayer, Path jar) {
		if (gameLayer == null || childLayer == null) return;
		Set<Module> gameModules = gameLayer.modules();
		Set<Module> childModules = childLayer.modules();
		for (Module child : childModules) {
			for (Module game : gameModules) {
				ModuleClassLoaderAccess.addReads(game, child); // inner mod -> outer class
				ModuleClassLoaderAccess.addReads(child, game); // outer class -> minecraft/neoforge
			}
		}
		LOGGER.debug("[AutoModpack] Linked JPMS module reads for {}: {} child module(s) <-> {} game module(s)", jar.getFileName(), childModules.size(),
				gameModules.size());
	}

	/**
	 * The GAME {@code ModuleLayer} at the bridge (announceLaunch) phase. Deliberately not
	 * {@code FMLLoader.getGameLayer()}: FML only publishes that in {@code beforeStart}, which runs
	 * after our launch plugin's {@code initializeLaunch}, so it is still null here. The GAME layer
	 * already exists though (TransformingClassLoader is built); reached via ModLauncher's own
	 * {@code IModuleLayerManager} through {@code Launcher.INSTANCE} by plain reflection.
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
			// If the layer manager is not reachable, reads-linking is simply skipped.
			return null;
		}
	}

	/**
	 * Resolves the GAME {@code TransformingClassLoader}. ModLauncher sets it as the thread context
	 * classloader before {@code launch()}, so it is in scope both at {@code announceLaunch} (our
	 * injected launch plugin's {@code initializeLaunch}) and during class transformation (the coremod
	 * fallback). Falls back to {@link #gameLayerOrNull()} - ModLauncher's own layer manager, not
	 * FML's - if the context loader is not (yet) a module classloader; unlike FML's game-layer
	 * accessor, that one carries no "mod discovery completed" precondition, so it works as a fallback
	 * at exactly the point where the context loader can't be relied on yet either.
	 */
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
	 * is a child loader, which already had first crack at the class (via its own {@code
	 * packageLookup}/{@code findClass}) and fell through here BECAUSE it failed - GAME would only
	 * route the same package straight back to a child (itself or another sharing this same set),
	 * so bouncing there again can only recurse. Breaking that hop here makes the cycle structurally
	 * impossible instead of merely unlikely.
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
}
