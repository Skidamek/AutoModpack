package pl.skidam.automodpack_loader_core_forge;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cpw.mods.modlauncher.api.ITransformationService;
import net.minecraftforge.forgespi.locating.IModFile;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.loader.ServiceJarIndex;
import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;
import pl.skidam.automodpack_loader_core_modlauncher.EarlyServiceBridgePlugin;
import pl.skidam.automodpack_loader_core_modlauncher.ModLauncherEarlyServiceBridge;

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
 * let a mod shipping only these load straight from the active projection with no copy.
 *
 * <p>
 * The ModLauncher-family machinery this shares with NeoForge fml4 - the child-layer build, the
 * child-layer registry, the {@code ITransformationService} forwarding engine and the GAME-classloader
 * bridge - lives in {@link ModLauncherEarlyServiceBridge}.
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

	// Every service hostable from the active projection without copying: the actively-run ones above,
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
			LOGGER.warn("[AutoModpack] Could not read the loader's early-service list; using the built-in fallback ({}: {})", t.getClass().getName(), t.getMessage());
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

	// bootstrap() must run exactly once; EarlyModLocator#scanMods() can be invoked more than once.
	private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);

	/**
	 * Builds one shared child SERVICE layer for every eligible modpack-folder early-service jar,
	 * registers it, instantiates each jar's declared {@code ITransformationService}(s) (forwarded
	 * lazily by {@link AutoModpackTransformationService}), and replays each jar's candidate/dependency
	 * locators so their real (inner) mods are discovered. Idempotent - safe to call from every
	 * {@link EarlyModLocator#scanMods} invocation.
	 */
	public static void bootstrap() {
		if (!BOOTSTRAPPED.compareAndSet(false, true)) return;

		// Early-service hosting serves the client's active projection; a dedicated server has none.
		if (Constants.LOADER_MANAGER.getEnvironmentType() != LoaderManagerService.EnvironmentType.CLIENT) return;

		try {
			// Preload owns what this launch loads; host early services only for jars on that list, so a
			// projection that was skipped (no active state, pinned conflicts, standard-mods duplicates)
			// is never half-bootstrapped.
			List<Path> earlyServiceJars = ModpackLoader.modsToLoad.stream().filter(EarlyServiceLayer::eligibleForInPlace).toList();
			if (earlyServiceJars.isEmpty()) return;

			Constants.LOGGER.info("[AutoModpack] Bootstrapping {} early-service mod(s) from the active projection in place", earlyServiceJars.size());

			ModuleLayer serviceLayer = EarlyServiceLayer.class.getModule().getLayer();
			if (serviceLayer == null) {
				LOGGER.warn("[AutoModpack] Not running on a module layer, cannot bootstrap early services in place");
				return;
			}

			List<Path> registered = ModLauncherEarlyServiceBridge.buildChildLayers(earlyServiceJars, serviceLayer, "AutoModpack Early Services");

			for (Path jar : registered) {
				for (String impl : serviceImpls(jar, TRANSFORMATION_SERVICE)) {
					try {
						ITransformationService service = (ITransformationService) Class.forName(impl, true, ModLauncherEarlyServiceBridge.classLoaderFor(jar))
								.getDeclaredConstructor()
								.newInstance();
						ModLauncherEarlyServiceBridge.addTransformationService(jar, service);
						LOGGER.debug("[AutoModpack] Instantiated in-place transformation service {} ({})", impl, jar.getFileName());
					} catch (Throwable t) {
						LOGGER.error("[AutoModpack] Failed to instantiate in-place transformation service {} from {}", impl, jar.getFileName(), t);
					}
				}
			}

			EarlyServiceBridgePlugin.registerFirst(EarlyServiceLayer::bridgeEarlyServicesToGameLayer);
		} catch (Throwable t) {
			LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
			// Bootstrap failures must crash the launch; swallowing them would boot without the pack (see the NeoForge twins).
			throw new RuntimeException("AutoModpack early-service bootstrap failed", t);
		}
	}

	// Per-jar facts (services, replayed impls) come from the shared ServiceJarIndex, scoped to
	// HANDLED_SERVICES so a legacy/removed SPI can't make a jar look unhostable.
	private static ServiceJarIndex.Facts<Void> facts(Path jar) {
		return ServiceJarIndex.facts(jar, HANDLED_SERVICES, ACTIVELY_RUN_SERVICES);
	}

	/** The impl class names of an {@link #ACTIVELY_RUN_SERVICES} service declared at the jar's root. */
	public static List<String> serviceImpls(Path jar, String serviceFile) {
		return facts(jar).implsOf(serviceFile);
	}

	/**
	 * Whether this jar's early services can all be run from the active projection: it must declare at
	 * least one actively-run service, ship no service outside {@link #HANDLEABLE_SERVICES}, and be a
	 * jar native Forge would itself exclude from mod discovery for the SERVICE layer. The last part is
	 * what makes in-place treatment safe - such a jar never loads as a plain mod natively, so replaying
	 * its locators can't double-load or shadow anything (e.g. CrashAssistant's outer/inner jars share
	 * one modId; loading both natively would be a duplicate-mod crash).
	 */
	public static boolean eligibleForInPlace(Path jar) {
		ServiceJarIndex.Facts<Void> facts = facts(jar);
		return !facts.serviceImpls().isEmpty() && HANDLEABLE_SERVICES.containsAll(facts.services()) && nativelyServiceClaimed(facts);
	}

	/** Whether native Forge would claim this jar for the SERVICE layer (see {@link #eligibleForInPlace}). */
	private static boolean nativelyServiceClaimed(ServiceJarIndex.Facts<?> facts) {
		for (String service : facts.serviceImpls().keySet()) {
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
		ClassLoader cl = ModLauncherEarlyServiceBridge.classLoaderFor(jar);
		if (cl == null) return;
		for (String impl : serviceImpls(jar, CANDIDATE_LOCATOR_SERVICE)) {
			try {
				Object locator = Class.forName(impl, true, cl).getDeclaredConstructor().newInstance();
				LOGGER.debug("[AutoModpack] Running in-place candidate locator {} from {}", impl, jar.getFileName());
				List<?> result = (List<?>) locator.getClass().getMethod("scanMods").invoke(locator);
				out.addAll(result);
			} catch (Throwable t) {
				throw new IllegalStateException("[AutoModpack] Candidate locator " + impl + " from " + jar.getFileName() + " failed; refusing to continue with the mod silently missing", t);
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
		ClassLoader cl = ModLauncherEarlyServiceBridge.classLoaderFor(jar);
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
			throw new IllegalStateException("[AutoModpack] Dependency locator " + impl + " from " + jar.getFileName() + " failed; refusing to continue with the mod silently missing", t);
		}
		try {
			LOGGER.debug("[AutoModpack] Running in-place dependency locator {} from {}", impl, jar.getFileName());
			@SuppressWarnings("unchecked")
			List<IModFile> result = (List<IModFile>) scanMods.invoke(locator, loadedMods);
			if (result != null) out.addAll(result);
			return true;
		} catch (Throwable t) {
			throw new IllegalStateException("[AutoModpack] Dependency locator " + impl + " from " + jar.getFileName() + " failed; refusing to continue with the mod silently missing", t);
		}
	}

	/** Every currently-registered early-service jar path (for replay loops in the locators). */
	public static Set<Path> registeredJars() {
		return ModLauncherEarlyServiceBridge.registeredJars();
	}

	/**
	 * Points the GAME classloader at each in-place early-service jar's child SERVICE layer, wiring
	 * {@code cpw.mods.cl} parentLoaders/resolvedRoots and JPMS module reads so GAME-layer code (e.g.
	 * Mixin) can see and load the early-service jar's outer classes/resources.
	 */
	public static void bridgeEarlyServicesToGameLayer() {
		// Every registered jar is one native Forge would exclude from mod discovery (see
		// eligibleForInPlace), so none of them has a GAME-layer twin to shadow - bridge them all.
		ModLauncherEarlyServiceBridge.bridgeEarlyServicesToGameLayer(jar -> false);
	}

	public static boolean isEarlyServiceJar(Path jar) {
		return ModLauncherEarlyServiceBridge.isEarlyServiceJar(jar);
	}

}
