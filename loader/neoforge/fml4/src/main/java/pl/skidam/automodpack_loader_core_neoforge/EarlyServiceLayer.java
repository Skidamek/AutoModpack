package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.coremod.ICoreMod;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.utils.FileInspection;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static pl.skidam.automodpack_core.Constants.LOGGER;

/**
 * Holds the per-jar child SERVICE module layers built by {@link EarlyServiceBootstrapper}
 * for neo/forge mods that ship "early services" inside the selected modpack folder, and
 * replays their mod-locating services (which only the loader's SERVICE layer would
 * normally run) into AutoModpack's own discovery so the mods load in place - without
 * being copied into the standard {@code mods/} directory.
 *
 * <p>{@link EarlyServiceBootstrapper} fires the {@code GraphicsBootstrapper}s. This class
 * exposes the same correctly-named classloaders so {@link EarlyModLocator} can run each
 * jar's {@code IModFileCandidateLocator}s and {@link LazyModLocator} its
 * {@code IDependencyLocator}s, loading whatever inner mods they provide.
 */
public final class EarlyServiceLayer {

    private EarlyServiceLayer() {}

    public static final String GRAPHICS_BOOTSTRAPPER_SERVICE = "META-INF/services/net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper";
    public static final String CANDIDATE_LOCATOR_SERVICE = "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator";
    public static final String DEPENDENCY_LOCATOR_SERVICE = "META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator";
    public static final String LANGUAGE_LOADER_SERVICE = "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader";
    public static final String COREMOD_SERVICE = "META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod";
    public static final String TRANSFORMATION_SERVICE = "META-INF/services/cpw.mods.modlauncher.api.ITransformationService";

    // Services that require active work to run in place: the GraphicsBootstrapper is fired, the
    // candidate/dependency locators are invoked, and coremod / transformation-service transformers
    // are forwarded (see AutoModpackCoreMod). A jar must declare at least one of these at its root to
    // be worth bootstrapping in place. These are also exactly the services whose impl class names we
    // read. A mod's ITransformationService is nearly always just an early-loading vehicle with empty
    // transformers() (asynclogger, Sinytra Connector, CrashAssistant all ship one); we forward its
    // transformers for the rare case they aren't, and its outer classes reach the GAME layer via the
    // bridge like any other early service.
    static final List<String> ACTIVELY_RUN_SERVICES = List.of(
            GRAPHICS_BOOTSTRAPPER_SERVICE, CANDIDATE_LOCATOR_SERVICE, DEPENDENCY_LOCATOR_SERVICE,
            COREMOD_SERVICE, TRANSFORMATION_SERVICE);

    // Every service we can host from the modpack folder, so a mod shipping only these never needs
    // copying: the actively-run ones above, plus language loaders (passive - picked up from the
    // GAME layer, no work of ours). Single source of truth, consumed by both the copy decision
    // (getWorkaroundMods) and the in-place bootstrapper.
    public static final Set<String> HANDLEABLE_SERVICES = Stream
            .concat(ACTIVELY_RUN_SERVICES.stream(), Stream.of(LANGUAGE_LOADER_SERVICE))
            .collect(Collectors.toUnmodifiableSet());

    // Maps each handled modpack jar to the classloader of the child service layer that
    // holds its early-service classes. Populated by EarlyServiceBootstrapper.
    private static final Map<Path, ClassLoader> JAR_CLASSLOADERS = new ConcurrentHashMap<>();

    // The GAME-layer bridge must run exactly once, from the injected launch plugin's
    // initializeLaunch (see EarlyServiceBridgePlugin), before Mixin loads any outer class.
    private static final AtomicBoolean GAME_BRIDGE_DONE = new AtomicBoolean(false);

    static void register(Path jar, ClassLoader serviceClassLoader) {
        JAR_CLASSLOADERS.put(canonical(jar), serviceClassLoader);
    }

    public static boolean isEarlyServiceJar(Path jar) {
        return jar != null && JAR_CLASSLOADERS.containsKey(canonical(jar));
    }

    private static ClassLoader classLoaderFor(Path jar) {
        return jar == null ? null : JAR_CLASSLOADERS.get(canonical(jar));
    }

    // Stable key so a jar matches whether reached via a relative or absolute path.
    private static Path canonical(Path jar) {
        try {
            return jar.toRealPath();
        } catch (Exception e) {
            return jar.toAbsolutePath().normalize();
        }
    }

    // Per-jar facts derived from a single jar mount, cached for the JVM's life (jar content is
    // immutable for the run). Without this the bootstrapper and both locators each re-open the
    // same jar to re-derive the same booleans/service lists - ~10 mounts per early-service jar.
    private record JarInfo(boolean activelyRunInPlace,           // an ACTIVELY_RUN service exists at root
                           Set<String> services,                 // known services at root + nested jarjar
                           Map<String, List<String>> serviceImpls, // impl class names per ACTIVELY_RUN service
                           boolean standalone,                   // root META-INF/neoforge.mods.toml present
                           boolean coremod) {}                   // ships at least one ICoreMod impl

    private static final Map<Path, JarInfo> JAR_INFO = new ConcurrentHashMap<>();

    private static JarInfo info(Path jar) {
        return JAR_INFO.computeIfAbsent(canonical(jar), EarlyServiceLayer::inspect);
    }

    private static JarInfo inspect(Path jar) {
        boolean activelyRun = false;
        Set<String> services = Set.of();
        Map<String, List<String>> impls = new HashMap<>();
        boolean standalone = false;
        try (FileSystem fs = FileSystems.newFileSystem(jar)) {
            // Explicit "neoforge": this runs from the early-service bootstrapper, before
            // Constants.LOADER is set, so we must name the live service namespace ourselves
            // (this module is always NeoForge). Forge-namespace service files are inert here.
            services = FileInspection.getSpecificServices(fs, "neoforge");
            standalone = Files.exists(fs.getPath("META-INF/neoforge.mods.toml"));
            for (String service : ACTIVELY_RUN_SERVICES) {
                if (Files.exists(fs.getPath(service))) {
                    activelyRun = true;
                    impls.put(service, readServiceImpls(fs, service));
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
    public static void runCandidateLocators(Path jar, ILaunchContext context, IDiscoveryPipeline pipeline) {
        ClassLoader cl = classLoaderFor(jar);
        if (cl == null) return;
        for (String impl : serviceImpls(jar, CANDIDATE_LOCATOR_SERVICE)) {
            try {
                IModFileCandidateLocator locator = (IModFileCandidateLocator) Class.forName(impl, true, cl)
                        .getDeclaredConstructor().newInstance();
                LOGGER.info("[AutoModpack] Running in-place candidate locator {} from {}", impl, jar.getFileName());
                locator.findCandidates(context, pipeline);
            } catch (Throwable t) {
                LOGGER.error("[AutoModpack] Failed to run candidate locator {} from {}", impl, jar.getFileName(), t);
            }
        }
    }

    /**
     * Runs the {@code IDependencyLocator}s declared inside an early-service jar. This is
     * how mods like Ixeris load their real (inner) mod jar.
     */
    public static void runDependencyLocators(Path jar, List<?> loadedMods, IDiscoveryPipeline pipeline) {
        ClassLoader cl = classLoaderFor(jar);
        if (cl == null) return;
        @SuppressWarnings("unchecked")
        List<net.neoforged.neoforgespi.locating.IModFile> mods =
                (List<net.neoforged.neoforgespi.locating.IModFile>) loadedMods;
        for (String impl : serviceImpls(jar, DEPENDENCY_LOCATOR_SERVICE)) {
            try {
                IDependencyLocator locator = (IDependencyLocator) Class.forName(impl, true, cl)
                        .getDeclaredConstructor().newInstance();
                LOGGER.info("[AutoModpack] Running in-place dependency locator {} from {}", impl, jar.getFileName());
                locator.scanMods(mods, pipeline);
            } catch (Throwable t) {
                LOGGER.error("[AutoModpack] Failed to run dependency locator {} from {}", impl, jar.getFileName(), t);
            }
        }
    }

    /**
     * Whether this jar's early services can all be run from the modpack folder. It must declare at
     * its root at least one service we actively run in place (a {@code GraphicsBootstrapper}, a
     * candidate/dependency locator, or a coremod's {@code ICoreMod}) AND ship no service outside
     * {@link #HANDLEABLE_SERVICES}. Anything else is left for the copy-to-standard path rather than
     * being half-loaded in place.
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
     * Instantiates the {@code ICoreMod}s and {@code ITransformationService}s shipped by the
     * registered early-service jars (from their child SERVICE layer, where the outer classes live)
     * and collects their transformers. Called by {@link AutoModpackCoreMod} during FML's
     * {@code transformers()} pass.
     *
     * <p>This is how a modpack-folder coremod (e.g. Sinytra Connector, whose own mixins rely on its
     * coremod to remap their {@code @Shadow} targets) runs in place. FML/ModLauncher only collect
     * these transformers from layers built before the GAME layer; a modpack jar reaches at best the
     * GAME layer, so its own coremod/transformation service is never scanned. AutoModpack, however,
     * sits on the SERVICE layer and IS scanned - so it forwards the modpack services' transformers
     * as its own. (A transformation service's transformers are usually empty - it is only an
     * early-loading vehicle - but forwarded for the rare case they aren't.)
     */
    public static List<ITransformer<?>> collectForwardedTransformers() {
        List<ITransformer<?>> transformers = new ArrayList<>();
        for (Map.Entry<Path, ClassLoader> entry : JAR_CLASSLOADERS.entrySet()) {
            Path jar = entry.getKey();
            ClassLoader cl = entry.getValue();

            // A coremod's transformers (e.g. Sinytra Connector's @Shadow name->SRG remap that its
            // own mixins need to apply). Most early-service jars ship none.
            for (String impl : serviceImpls(jar, COREMOD_SERVICE)) {
                try {
                    ICoreMod coremod = (ICoreMod) Class.forName(impl, true, cl)
                            .getDeclaredConstructor().newInstance();
                    int before = transformers.size();
                    for (ITransformer<?> transformer : coremod.getTransformers()) {
                        transformers.add(transformer);
                    }
                    LOGGER.info("[AutoModpack] Forwarding {} transformer(s) from in-place coremod {} ({})",
                            transformers.size() - before, impl, jar.getFileName());
                } catch (Throwable t) {
                    LOGGER.error("[AutoModpack] Failed to run in-place coremod {} from {}", impl, jar.getFileName(), t);
                }
            }

            // A transformation service's transformers. Almost always empty - the service is only a
            // vehicle to load early (asynclogger, Connector, CrashAssistant all ship such a no-op
            // one) - but forwarded so any that aren't empty still run in place.
            for (String impl : serviceImpls(jar, TRANSFORMATION_SERVICE)) {
                try {
                    ITransformationService service = (ITransformationService) Class.forName(impl, true, cl)
                            .getDeclaredConstructor().newInstance();
                    int before = transformers.size();
                    for (ITransformer<?> transformer : service.transformers()) {
                        transformers.add(transformer);
                    }
                    if (transformers.size() > before) {
                        LOGGER.info("[AutoModpack] Forwarding {} transformer(s) from in-place transformation service {} ({})",
                                transformers.size() - before, impl, jar.getFileName());
                    }
                } catch (Throwable t) {
                    LOGGER.error("[AutoModpack] Failed to run in-place transformation service {} from {}", impl, jar.getFileName(), t);
                }
            }
        }
        return transformers;
    }

    /**
     * Points the GAME classloader at each in-place early-service jar's child SERVICE layer - the
     * layer its {@code GraphicsBootstrapper} actually fired on - so the outer classes resolve there,
     * in place, with no GAME-library copy.
     *
     * <p>The inner mod (on the GAME layer) references its outer jar's classes; natively those resolve
     * because the outer jar sits on the SERVICE layer, a GAME ancestor. Our child layer is only a
     * sibling, so we add the child as a {@code parentLoaders} delegate for each of the outer jar's
     * packages (and drop any GAME {@code packageLookup} entry, should a copy ever exist). Then every
     * outer class - whether referenced structurally during Mixin config prep (Sodium's
     * {@code Workarounds}) or read for its bootstrap-time static state at runtime (asynclogger's
     * {@code LoggerConfigurator.config}) - resolves to the single, already-initialised child copy. No
     * second class, no split static state, no NPE.
     *
     * <p>Timing is the whole game. Mixin prepares every mod's config as the launch target starts,
     * loading those outer classes before any post-GAME mod hook exists. We beat that by running from
     * an {@link EarlyServiceBridgePlugin} launch plugin we inject into ModLauncher: its
     * {@code initializeLaunch} fires during {@code announceLaunch}, after the GAME
     * {@code TransformingClassLoader} is built but before the game main (and thus before Mixin's
     * prep). Idempotent via {@link #GAME_BRIDGE_DONE} - but the flag is claimed only once we actually
     * hold the GAME classloader, so a too-early call (a launch plugin's {@code addResources}) does
     * not poison the real one.
     *
     * <p>This bridges both classes ({@code parentLoaders}) and resources ({@code resolvedRoots}), so
     * it works for a plain split early service (Sodium) AND for a coremod whose outer jar owns the
     * mixins (Sinytra Connector) - Mixin reads those mixin classes as bytecode resources. Only a
     * <em>standalone</em> coremod (itself a mod, added to the GAME layer as its real self) is skipped.
     */
    public static void bridgeEarlyServicesToGameLayer() {
        if (GAME_BRIDGE_DONE.get()) return;

        ClassLoader gameClassLoader = resolveGameClassLoader();
        if (!(gameClassLoader instanceof cpw.mods.cl.ModuleClassLoader)) {
            // The GAME TransformingClassLoader is not in scope yet (e.g. a launch plugin's
            // addResources runs before it is built). A later caller - our launch plugin's
            // initializeLaunch, or the coremod fallback - retries, so do NOT consume the one-shot flag.
            return;
        }

        // We hold the real GAME classloader; claim the bridge exactly once.
        if (!GAME_BRIDGE_DONE.compareAndSet(false, true)) return;

        Map<String, ClassLoader> gameParentLoaders = ModuleClassLoaderAccess.parentLoaders(gameClassLoader);
        Map<String, Object> gamePackageLookup = ModuleClassLoaderAccess.packageLookup(gameClassLoader);
        Map<String, Object> gameResolvedRoots = ModuleClassLoaderAccess.resolvedRoots(gameClassLoader);

        for (Map.Entry<Path, ClassLoader> entry : JAR_CLASSLOADERS.entrySet()) {
            Path jar = entry.getKey();
            ClassLoader childLoader = entry.getValue();
            // A standalone coremod that is itself a mod (root neoforge.mods.toml) was added to the
            // GAME layer as its real self by EarlyModLocator, so its outer classes already resolve
            // there - bridging would redirect them to the child. Everything else - plain early
            // services (Sodium) AND non-standalone coremods whose outer jar owns the mixins (Sinytra
            // Connector) - resolves through the bridge, with no game-library copy.
            if ((isCoremodJar(jar) && isStandaloneModFile(jar)) || !(childLoader instanceof cpw.mods.cl.ModuleClassLoader)) continue;

            try {
                int bridged = 0;
                for (String pkg : ModuleClassLoaderAccess.packageLookup(childLoader).keySet()) {
                    // Classes: route the outer package to the child (single class identity). remove()
                    // clears any stale GAME mapping (normally none, since we add no copy).
                    gameParentLoaders.put(pkg, childLoader);
                    gamePackageLookup.remove(pkg);
                    bridged++;
                }
                // Resources: findResourceList never consults parentLoaders, only the loader's own
                // resolvedRoots, so add the child's jar references too. Without this, Mixin can't read
                // an outer-jar mixin class's bytecode (a resource) and config prep crashes - the sole
                // reason coremods like Connector needed a GAME-library copy on resolvedRoots before.
                gameResolvedRoots.putAll(ModuleClassLoaderAccess.resolvedRoots(childLoader));
                // The child layer was built at the early-window phase, before the GAME layer existed,
                // so its parents are only [SERVICE, BOOT] - it cannot see minecraft/neoforge. Now that
                // GAME is built, point the child's fallback at it, so an outer class loaded on the
                // child (e.g. a Connector mixin/loader class referencing net.minecraft.*) resolves
                // those GAME classes. This is what the game-library copy got for free by living on GAME.
                ((cpw.mods.cl.ModuleClassLoader) childLoader).setFallbackClassLoader(gameClassLoader);
                LOGGER.info("[AutoModpack] Bridged {} outer package(s) of {} to its early-service layer for in-place class/resource sharing", bridged, jar.getFileName());
            } catch (Throwable t) {
                LOGGER.error("[AutoModpack] Failed to bridge {} to the GAME layer", jar.getFileName(), t);
            }
        }
    }

    /**
     * Resolves the GAME {@code TransformingClassLoader}. ModLauncher sets it as the thread context
     * classloader before {@code launch()}, so it is in scope both at {@code announceLaunch} (our
     * injected launch plugin's {@code initializeLaunch}) and during class transformation (the coremod
     * fallback) - crucially without {@code FMLLoader.getGameLayer()}'s "mod discovery completed"
     * precondition, which is not yet met when a launch plugin's {@code addResources} runs. Falls back
     * to the FML game layer if the context loader is not (yet) a module classloader.
     */
    private static ClassLoader resolveGameClassLoader() {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx instanceof cpw.mods.cl.ModuleClassLoader) return ctx;
        try {
            ModuleLayer gameLayer = FMLLoader.getGameLayer();
            if (gameLayer != null) {
                for (Module module : gameLayer.modules()) {
                    ClassLoader cl = module.getClassLoader();
                    if (cl instanceof cpw.mods.cl.ModuleClassLoader) return cl;
                }
            }
        } catch (Throwable ignored) {
            // getGameLayer throws until FML mod discovery completes; the context loader covers us.
        }
        return null;
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

    /** Reads the implementation class names listed in a {@code META-INF/services/...} file. */
    private static List<String> readServiceImpls(FileSystem fs, String serviceFile) {
        List<String> impls = new ArrayList<>();
        Path service = fs.getPath(serviceFile);
        if (!Files.exists(service)) return impls;
        try (InputStream is = Files.newInputStream(service);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
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
