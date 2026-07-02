package pl.skidam.automodpack_loader_core_forge;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.SecureJar;
import net.minecraftforge.forgespi.locating.IModFile;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.HashUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * Forge's analog of the NeoForge fml4 {@code EarlyServiceLayer}: both loaders still run on the
 * original ModLauncher/securejarhandler module-layer machinery ({@code cpw.mods.*}), so the core
 * mechanism - building a shared child SERVICE {@link ModuleLayer} for the selected modpack's
 * early-service jars, then bridging the GAME classloader to it - is unchanged. Two things differ
 * because Forge's own SPI surface is smaller/older than NeoForge's:
 *
 * <ul>
 *   <li>No {@code GraphicsBootstrapper} equivalent - Forge's {@code ImmediateWindowProvider} is not
 *       one of the loader's own early-scanned services, so there is no hook to build the child
 *       layer before mod discovery starts the way {@link pl.skidam.automodpack_loader_core_forge}
 *       (NeoForge's) {@code EarlyServiceBootstrapper} does. Instead {@link #bootstrap} runs from
 *       {@link EarlyModLocator#scanMods}, the earliest point AutoModpack itself is invoked (it is
 *       itself found early via its own {@code IModLocator} service file).</li>
 *   <li>No {@code ICoreMod} equivalent worth supporting - Forge's coremod system
 *       ({@code ICoreModProvider}) is a JavaScript-transformer mechanism, not a per-jar SPI, and is
 *       vanishingly rare in modern mods; skipped entirely.</li>
 * </ul>
 *
 * <p>What's left - {@code ITransformationService} (forwarded by {@link AutoModpackTransformationService}),
 * {@code IModLocator} (Forge's {@code IModFileCandidateLocator}), and {@code IDependencyLocator} - is
 * otherwise identical in spirit to the NeoForge fml4 port: a mod that ships only these loads straight
 * from the modpack folder, no copy into the standard {@code mods/} directory.
 */
public final class EarlyServiceLayer {

    private EarlyServiceLayer() {}

    public static final String CANDIDATE_LOCATOR_SERVICE = "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator";
    public static final String DEPENDENCY_LOCATOR_SERVICE = "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator";
    public static final String LANGUAGE_LOADER_SERVICE = "META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider";
    public static final String TRANSFORMATION_SERVICE = "META-INF/services/cpw.mods.modlauncher.api.ITransformationService";

    // Services that require active work to run in place: the candidate/dependency locators are
    // replayed, and a transformation service's lifecycle is forwarded (see AutoModpackTransformationService).
    // A mod's ITransformationService is nearly always just an early-loading vehicle with empty
    // transformers(); we forward it anyway for the rare case it isn't, and its outer classes reach
    // the GAME layer via the bridge like any other early service.
    static final List<String> ACTIVELY_RUN_SERVICES = List.of(
            CANDIDATE_LOCATOR_SERVICE, DEPENDENCY_LOCATOR_SERVICE, TRANSFORMATION_SERVICE);

    // Every service we can host from the modpack folder, so a mod shipping only these never needs
    // copying: the actively-run ones above, plus language providers (passive - picked up from the
    // GAME layer, no work of ours).
    public static final Set<String> HANDLEABLE_SERVICES = Stream
            .concat(ACTIVELY_RUN_SERVICES.stream(), Stream.of(LANGUAGE_LOADER_SERVICE))
            .collect(Collectors.toUnmodifiableSet());

    // Every service the running Forge version actually promotes to the SERVICE layer before mod
    // discovery - read from the loader itself (ModDirTransformerDiscoverer.SERVICES on 1.20.1+;
    // older versions, e.g. 1.18.2, hardcode the check instead of exposing a field) so it is exact
    // for THIS version rather than a hand-maintained list.
    private static final Set<String> HANDLED_SERVICES = computeHandledServices();

    /** Service files this loader version actually discovers/runs; superset of {@link #HANDLEABLE_SERVICES}. */
    public static Set<String> knownServices() {
        return HANDLED_SERVICES;
    }

    private static Set<String> computeHandledServices() {
        Set<String> handled = new HashSet<>();
        try {
            Class<?> discoverer = Class.forName("net.minecraftforge.fml.loading.ModDirTransformerDiscoverer");
            java.lang.reflect.Field field = discoverer.getDeclaredField("SERVICES");
            field.setAccessible(true);
            Object services = field.get(null);
            if (services instanceof Set<?> names) {
                for (Object name : names) {
                    handled.add(String.valueOf(name));
                }
            }
        } catch (Throwable t) {
            // No SERVICES field on this Forge version (e.g. 1.18.2, which hardcodes the check
            // instead): fall back to the services we know Forge handles.
            LOGGER.warn("[AutoModpack] Could not read the loader's early-service list; using the built-in fallback", t);
            handled.add(CANDIDATE_LOCATOR_SERVICE);
            handled.add(TRANSFORMATION_SERVICE);
        }
        handled.add(DEPENDENCY_LOCATOR_SERVICE);
        handled.add(LANGUAGE_LOADER_SERVICE);
        return Set.copyOf(handled);
    }

    // The classloader of a handled modpack jar's child SERVICE layer (which holds its early-service
    // classes) plus that layer itself (so the GAME-layer bridge can wire up JPMS read edges between
    // the child module(s) and the GAME modules) - always written together by register() and mostly
    // read together, so one map keyed by jar keeps them from drifting out of sync.
    private record JarService(ClassLoader classLoader, ModuleLayer layer) {}

    // Maps each handled modpack jar to its child-layer classloader/layer. Populated by bootstrap().
    private static final Map<Path, JarService> JAR_SERVICES = new ConcurrentHashMap<>();

    // ITransformationService instances instantiated in place per jar, kept so the forwarding service
    // injected into ModLauncher (AutoModpackTransformationService) can run their completeScan at the
    // native, post-discovery time - when its returned resources still reach the GAME/PLUGIN layers.
    private static final Map<Path, List<ITransformationService>> TRANSFORMATION_SERVICES = new ConcurrentHashMap<>();

    // The whole bootstrap (child layer construction + locator replay) must run exactly once - unlike
    // NeoForge's GraphicsBootstrapper, EarlyModLocator's scanMods() can in principle be invoked more
    // than once by a caller doing its own re-discovery.
    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);

    // The GAME-layer bridge must run exactly once, from the injected launch plugin's
    // initializeLaunch (see EarlyServiceBridgePlugin), before Mixin loads any outer class.
    private static final AtomicBoolean GAME_BRIDGE_DONE = new AtomicBoolean(false);

    /**
     * Builds one shared child SERVICE layer for every eligible modpack-folder early-service jar,
     * registers it, instantiates each jar's declared {@code ITransformationService}(s) (forwarded
     * lazily by {@link AutoModpackTransformationService}), and replays each jar's candidate/dependency
     * locators so their real (inner) mods are discovered. Idempotent - safe to call from every
     * {@link EarlyModLocator#scanMods} invocation.
     */
    public static void bootstrap(Path gameDir) {
        if (!BOOTSTRAPPED.compareAndSet(false, true)) return;

        try {
            Path modpackMods = resolveSelectedModpackMods(gameDir);
            if (modpackMods == null || !Files.isDirectory(modpackMods)) {
                return;
            }

            List<Path> earlyServiceJars = new ArrayList<>();
            Set<String> standardModHashes = null;
            try (Stream<Path> stream = Files.list(modpackMods)) {
                for (Path jar : stream.filter(EarlyServiceLayer::isJar).toList()) {
                    if (!eligibleForInPlace(jar)) {
                        continue;
                    }
                    if (standardModHashes == null) {
                        standardModHashes = hashStandardMods(gameDir);
                    }
                    String hash = HashUtils.getHash(jar);
                    if (hash != null && standardModHashes.contains(hash)) {
                        continue;
                    }
                    earlyServiceJars.add(jar);
                }
            }

            if (earlyServiceJars.isEmpty()) {
                return;
            }

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
                        ITransformationService service = (ITransformationService) Class.forName(impl, true, classLoaderFor(jar))
                                .getDeclaredConstructor().newInstance();
                        TRANSFORMATION_SERVICES.computeIfAbsent(jar, k -> new ArrayList<>()).add(service);
                        LOGGER.info("[AutoModpack] Instantiated in-place transformation service {} ({})", impl, jar.getFileName());
                    } catch (Throwable t) {
                        LOGGER.error("[AutoModpack] Failed to instantiate in-place transformation service {} from {}", impl, jar.getFileName(), t);
                    }
                }
            }

            EarlyServiceBridgePlugin.ensureRunsFirst();
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
        try {
            SecureJar[] secureJars = new SecureJar[jars.size()];
            List<String> moduleNames = new ArrayList<>(jars.size());
            for (int i = 0; i < jars.size(); i++) {
                SecureJar secureJar = SecureJar.from(jars.get(i));
                secureJars[i] = secureJar;
                moduleNames.add(secureJar.name());
            }

            Configuration configuration = serviceLayer.configuration()
                    .resolveAndBind(JarModuleFinder.of(secureJars), ModuleFinder.of(), moduleNames);

            List<ModuleLayer> parentLayers = flattenParents(serviceLayer);

            ModuleClassLoader classLoader = new ModuleClassLoader("AutoModpack Early Services", configuration, parentLayers);
            classLoader.setFallbackClassLoader(EarlyServiceLayer.class.getClassLoader());
            ModuleLayer childLayer = ModuleLayer.defineModules(configuration, List.of(serviceLayer), name -> classLoader).layer();

            for (Path jar : jars) {
                JAR_SERVICES.put(canonical(jar), new JarService(classLoader, childLayer));
            }
        } catch (Throwable t) {
            LOGGER.error("[AutoModpack] Could not build a shared service layer for the early-service mods; none of them will be bootstrapped in place", t);
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

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".jar");
    }

    private static Path resolveSelectedModpackMods(Path gameDir) {
        String selected = null;

        try (InputStream is = EarlyServiceLayer.class.getResourceAsStream("/" + Constants.clientConfigFileOverrideResource)) {
            if (is != null) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                pl.skidam.automodpack_core.config.Jsons.ClientConfigFieldsV2 config =
                        pl.skidam.automodpack_core.config.ConfigTools.load(json, pl.skidam.automodpack_core.config.Jsons.ClientConfigFieldsV2.class);
                if (config != null) selected = config.selectedModpack;
            }
        } catch (Exception ignored) {
        }

        if (selected == null) {
            pl.skidam.automodpack_core.config.Jsons.ClientConfigFieldsV2 config =
                    pl.skidam.automodpack_core.config.ConfigTools.load(gameDir.resolve(Constants.clientConfigFile), pl.skidam.automodpack_core.config.Jsons.ClientConfigFieldsV2.class);
            if (config != null) selected = config.selectedModpack;
        }

        if (selected == null || selected.isBlank()) {
            return null;
        }

        return gameDir.resolve(Constants.modpacksDir).resolve(selected).resolve("mods");
    }

    private static Set<String> hashStandardMods(Path gameDir) {
        Set<String> hashes = new HashSet<>();
        Path modsDir = gameDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return hashes;
        }
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(EarlyServiceLayer::isJar).forEach(jar -> {
                String hash = HashUtils.getHash(jar);
                if (hash != null) hashes.add(hash);
            });
        } catch (Exception e) {
            LOGGER.debug("[AutoModpack] Failed to list standard mods directory while bootstrapping early services", e);
        }
        return hashes;
    }

    public static boolean isEarlyServiceJar(Path jar) {
        return jar != null && JAR_SERVICES.containsKey(canonical(jar));
    }

    private static ClassLoader classLoaderFor(Path jar) {
        if (jar == null) return null;
        JarService service = JAR_SERVICES.get(canonical(jar));
        return service == null ? null : service.classLoader();
    }

    // Purely lexical (no toRealPath()): both the writer (bootstrap(), from Files.list(modpackMods))
    // and every reader derive their paths from listing the same modpack mods/ folder with no symlink
    // indirection between them, so lexical equality already holds.
    private static Path canonical(Path jar) {
        return jar.toAbsolutePath().normalize();
    }

    // Per-jar facts derived from a single jar mount, cached for the JVM's life.
    private record JarInfo(boolean activelyRunInPlace, Set<String> services, Map<String, List<String>> serviceImpls, boolean standalone) {}

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
            // Explicit "forge": this can run before Constants.LOADER is set, so we must name the
            // live service namespace ourselves.
            services = FileInspection.getSpecificServices(fs, "forge");
            services.retainAll(knownServices());
            standalone = Files.exists(fs.getPath("META-INF/mods.toml"));
            for (String service : ACTIVELY_RUN_SERVICES) {
                if (Files.exists(fs.getPath(service))) {
                    activelyRun = true;
                    impls.put(service, readServiceImpls(fs, service));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[AutoModpack] Could not inspect {}; not handling it in place", jar.getFileName(), e);
        }
        return new JarInfo(activelyRun, services, impls, standalone);
    }

    /** The impl class names of an {@link #ACTIVELY_RUN_SERVICES} service declared at the jar's root. */
    public static List<String> serviceImpls(Path jar, String serviceFile) {
        return info(jar).serviceImpls().getOrDefault(serviceFile, List.of());
    }

    public static boolean eligibleForInPlace(Path jar) {
        JarInfo info = info(jar);
        return info.activelyRunInPlace() && HANDLEABLE_SERVICES.containsAll(info.services());
    }

    /**
     * Whether the jar is itself a loadable mod - it declares a root {@code mods.toml} - as opposed to
     * a thin outer jar whose real mod is nested or absent (a split-jar shim). A standalone
     * early-service jar is added to the GAME layer as its real self by {@link EarlyModLocator} (like
     * any other mod), so it is excluded from the GAME-layer bridge; a non-standalone one has its outer
     * classes bridged there instead.
     */
    public static boolean isStandaloneModFile(Path jar) {
        return info(jar).standalone();
    }

    /**
     * Runs the {@code IModLocator}s declared inside an early-service jar, merging their results into
     * {@code out}. This is how a modpack-folder split-jar mod loads its real (inner) mod jar - the
     * loader would otherwise only run this from its own SERVICE layer, which AutoModpack never reaches.
     *
     * <p>Invoked by plain reflection, not a static {@code IModLocator} cast: {@code scanMods()}'s
     * return type is {@code List<IModFile>} on Forge 1.18.2 but {@code List<ModFileOrException>} (a
     * class that does not exist at all pre-1.19) from ~1.19 on - fine at the source level (generics
     * erase to a raw {@code List} either way), but this module compiles once against one forgespi
     * version for both, and a compiled reference to the newer type alone would fail to link on the
     * older one. Reflection avoids the compiled class ever naming a type the older jar lacks.
     */
    public static void runCandidateLocators(Path jar, List<Object> out) {
        ClassLoader cl = classLoaderFor(jar);
        if (cl == null) return;
        for (String impl : serviceImpls(jar, CANDIDATE_LOCATOR_SERVICE)) {
            try {
                Object locator = Class.forName(impl, true, cl).getDeclaredConstructor().newInstance();
                LOGGER.info("[AutoModpack] Running in-place candidate locator {} from {}", impl, jar.getFileName());
                List<?> result = (List<?>) locator.getClass().getMethod("scanMods").invoke(locator);
                out.addAll(result);
            } catch (Throwable t) {
                LOGGER.error("[AutoModpack] Failed to run candidate locator {} from {}", impl, jar.getFileName(), t);
            }
        }
    }

    /**
     * Runs the {@code IDependencyLocator}s declared inside an early-service jar, merging their
     * results into {@code out}. Reflective for the same reason as {@link #runCandidateLocators}: the
     * standalone {@code IDependencyLocator} interface does not exist at all pre-~1.19 (Forge 1.18.2
     * folds {@code scanMods(Iterable)} into {@code IModLocator} itself instead), so this shared module
     * must not name it in compiled bytecode.
     */
    public static void runDependencyLocators(Path jar, Iterable<IModFile> loadedMods, List<IModFile> out) {
        ClassLoader cl = classLoaderFor(jar);
        if (cl == null) return;
        for (String impl : serviceImpls(jar, DEPENDENCY_LOCATOR_SERVICE)) {
            try {
                Object locator = Class.forName(impl, true, cl).getDeclaredConstructor().newInstance();
                LOGGER.info("[AutoModpack] Running in-place dependency locator {} from {}", impl, jar.getFileName());
                @SuppressWarnings("unchecked")
                List<IModFile> result = (List<IModFile>) locator.getClass().getMethod("scanMods", Iterable.class).invoke(locator, loadedMods);
                out.addAll(result);
            } catch (Throwable t) {
                LOGGER.error("[AutoModpack] Failed to run dependency locator {} from {}", impl, jar.getFileName(), t);
            }
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
                    LOGGER.info("[AutoModpack] Ran in-place transformation-service {} for {} ({})", verb, service.getClass().getName(), entry.getKey().getFileName());
                } catch (Throwable t) {
                    LOGGER.error("[AutoModpack] In-place transformation service {} {} failed ({})", service.getClass().getName(), verb, entry.getKey().getFileName(), t);
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
                        LOGGER.info("[AutoModpack] Forwarded {} {} result(s) from in-place transformation service {} ({})",
                                produced.size(), verb, service.getClass().getName(), entry.getKey().getFileName());
                    }
                } catch (Throwable t) {
                    LOGGER.error("[AutoModpack] In-place transformation service {} {} failed ({})", service.getClass().getName(), verb, entry.getKey().getFileName(), t);
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
     * Points the GAME classloader at each in-place early-service jar's child SERVICE layer - see the
     * NeoForge fml4 {@code EarlyServiceLayer#bridgeEarlyServicesToGameLayer} javadoc for the full
     * rationale, which applies unchanged here (same {@code cpw.mods.cl} mechanism).
     */
    public static void bridgeEarlyServicesToGameLayer() {
        if (GAME_BRIDGE_DONE.get()) return;

        ClassLoader gameClassLoader = resolveGameClassLoader();
        if (!(gameClassLoader instanceof ModuleClassLoader)) {
            return;
        }

        if (!GAME_BRIDGE_DONE.compareAndSet(false, true)) return;

        Map<String, ClassLoader> gameParentLoaders = ModuleClassLoaderAccess.parentLoaders(gameClassLoader);
        Map<String, Object> gamePackageLookup = ModuleClassLoaderAccess.packageLookup(gameClassLoader);
        Map<String, Object> gameResolvedRoots = ModuleClassLoaderAccess.resolvedRoots(gameClassLoader);
        ModuleLayer gameLayer = gameLayerOrNull();

        for (Map.Entry<Path, JarService> entry : JAR_SERVICES.entrySet()) {
            Path jar = entry.getKey();
            ClassLoader childLoader = entry.getValue().classLoader();
            if (!(childLoader instanceof ModuleClassLoader)) continue;

            try {
                int bridged = 0;
                for (String pkg : ModuleClassLoaderAccess.packageLookup(childLoader).keySet()) {
                    gameParentLoaders.put(pkg, childLoader);
                    gamePackageLookup.remove(pkg);
                    bridged++;
                }
                gameResolvedRoots.putAll(ModuleClassLoaderAccess.resolvedRoots(childLoader));
                ((ModuleClassLoader) childLoader).setFallbackClassLoader(gameClassLoader);
                LOGGER.info("[AutoModpack] Bridged {} outer package(s) of {} to its early-service layer for in-place class/resource sharing", bridged, jar.getFileName());
                linkModuleReads(gameLayer, entry.getValue().layer(), jar);
            } catch (Throwable t) {
                LOGGER.error("[AutoModpack] Failed to bridge {} to the GAME layer", jar.getFileName(), t);
            }
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
        LOGGER.info("[AutoModpack] Linked JPMS module reads for {}: {} child module(s) <-> {} game module(s)",
                jar.getFileName(), childModules.size(), gameModules.size());
    }

    /**
     * The GAME {@code ModuleLayer} at the bridge (announceLaunch) phase, reached via ModLauncher's own
     * {@code IModuleLayerManager} (not {@code FMLLoader.getGameLayer()}, which is null this early on
     * Forge too - see the NeoForge fml4 javadoc for why).
     */
    private static ModuleLayer gameLayerOrNull() {
        try {
            Object launcher = ModuleClassLoaderAccess.launcherInstance();
            Object managerOpt = launcher.getClass().getMethod("findLayerManager").invoke(launcher);
            @SuppressWarnings("unchecked")
            IModuleLayerManager manager = ((java.util.Optional<IModuleLayerManager>) managerOpt).orElse(null);
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
