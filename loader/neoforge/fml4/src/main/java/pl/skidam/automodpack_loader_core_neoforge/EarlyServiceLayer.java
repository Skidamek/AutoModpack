package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.modlauncher.api.ITransformer;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.coremod.ICoreMod;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.HashUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    // Services that require active work to run in place: the GraphicsBootstrapper is fired, the
    // candidate/dependency locators are invoked, and a coremod's transformers are forwarded (see
    // AutoModpackCoreMod). A jar must declare at least one of these at its root to be worth
    // bootstrapping in place. These are also exactly the services whose impl class names we read.
    static final List<String> ACTIVELY_RUN_SERVICES = List.of(
            GRAPHICS_BOOTSTRAPPER_SERVICE, CANDIDATE_LOCATOR_SERVICE, DEPENDENCY_LOCATOR_SERVICE,
            COREMOD_SERVICE);

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

    // Maps each handled jar to the stripped game-library copy built for it (see
    // libraryCopyFor). Cached so we build each copy at most once.
    private static final Map<Path, Path> LIBRARY_COPIES = new ConcurrentHashMap<>();
    // Jars we tried and can't make a game-library copy for (e.g. split-package); remembered
    // so we don't retry. Separate set because ConcurrentHashMap forbids null values.
    private static final Set<Path> NO_LIBRARY_COPY = ConcurrentHashMap.newKeySet();

    // The GAME-layer bridge must run exactly once, the first time the launch target's class is
    // loaded (see GameGraphicsBootstrapTrigger).
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
     * Adds an early-service jar's stripped game-library copy (its outer classes, typed
     * {@code GAMELIBRARY}) to the GAME layer, if one could be built. Shared by both locators so the
     * add is expressed once; only <em>when</em> it is called differs (see {@link #isCoremodJar}).
     */
    public static void addLibraryCopy(Path jar, IDiscoveryPipeline pipeline) {
        Path libraryCopy = libraryCopyFor(jar);
        if (libraryCopy != null) {
            pipeline.addPath(libraryCopy, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        }
    }

    /**
     * Builds (once, cached) a stripped "game library" copy of an early-service outer jar.
     *
     * <p>Mods like Sodium split a thin outer jar - which holds classes the real inner mod
     * references (e.g. {@code Workarounds$Reference}) - from that inner mod, which the outer
     * jar's own locator loads onto the GAME layer. Natively the inner mod resolves those
     * classes because the outer jar sits on the loader's SERVICE layer, an ancestor of GAME.
     * Our in-place child layer is only a sibling of GAME, so the inner mod cannot see the
     * outer classes - and crucially Mixin reads them as bytecode <em>resources</em>, which a
     * mere {@code defineClass} on an ancestor classloader does not expose.
     *
     * <p>The fix is to put the outer jar's classes onto the GAME layer itself, as a plain
     * library. This copy therefore strips everything that would make the loader treat it as a
     * mod or re-run its services - the mod metadata, the {@code META-INF/services} entries and
     * the nested {@code META-INF/jarjar} mods - and marks the manifest {@code FMLModType:
     * GAMELIBRARY} so the loader adds it to the GAME layer as a non-mod library.
     *
     * <p>The copy must not share a package with the inner mod (both end up on the GAME layer),
     * or the module system throws a split-package {@code LayerInstantiationException} at layer
     * build time - which would take down <em>every</em> mod, not just this one. Such a mod is
     * not loadable in place this way; we skip the copy and tell the user to force-copy it.
     *
     * <p>The copy is cached under {@code automodpack/cache} with a content-hash-stable name, so
     * it survives a crash (no {@code deleteOnExit} leak) and is reused across launches.
     *
     * @return the stripped copy, or {@code null} if it could not be built or would split-package
     *         (the mod then needs the copy-to-standard workaround).
     */
    public static Path libraryCopyFor(Path jar) {
        if (jar == null) return null;
        Path key = canonical(jar);
        Path existing = LIBRARY_COPIES.get(key);
        if (existing != null) return existing;
        if (NO_LIBRARY_COPY.contains(key)) return null;
        synchronized (LIBRARY_COPIES) {
            existing = LIBRARY_COPIES.get(key);
            if (existing != null) return existing;
            if (NO_LIBRARY_COPY.contains(key)) return null;
            Path copy;
            try {
                copy = buildLibraryCopy(jar);
            } catch (Exception e) {
                LOGGER.error("[AutoModpack] Could not build a game-library copy of {}; it will need the copy-to-standard fallback", jar.getFileName(), e);
                copy = null;
            }
            if (copy != null) {
                LIBRARY_COPIES.put(key, copy);
            } else {
                NO_LIBRARY_COPY.add(key); // remember known-bad jars so we don't retry
            }
            return copy;
        }
    }

    private static Path buildLibraryCopy(Path jar) throws Exception {
        String baseName = jar.getFileName().toString();
        if (baseName.toLowerCase().endsWith(".jar")) baseName = baseName.substring(0, baseName.length() - 4);

        // Content-hash-stable cache location, reused across launches.
        Path cacheDir = Constants.cacheDir.resolve("early-service-libs");
        Files.createDirectories(cacheDir);
        String hash = HashUtils.getHash(jar);
        String tag = hash == null ? Long.toHexString(Files.size(jar)) : hash.substring(0, Math.min(16, hash.length()));
        Path copy = cacheDir.resolve("automodpack-lib-" + baseName + "-" + tag + ".jar");
        if (Files.isRegularFile(copy) && Files.size(copy) > 0) {
            return copy; // already built for this exact content
        }

        Manifest manifest = readManifest(jar);
        manifest.getMainAttributes().putValue("FMLModType", "GAMELIBRARY");
        // Strip the automatic-module hint so the copy gets a name derived from its own file
        // name (automodpack-lib-...), keeping it distinct from the real inner mod's module.
        manifest.getMainAttributes().remove(new Attributes.Name("Automatic-Module-Name"));

        // Build into a temp file first, so a crash mid-write never leaves a half-written cache
        // entry that a later launch would trust.
        Path tmp = Files.createTempFile(cacheDir, baseName + "-", ".jar.tmp");
        Set<String> outerPackages = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar));
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(tmp), manifest)) {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || shouldStripFromLibrary(name)) {
                    continue;
                }
                String pkg = packageOfClassEntry(name);
                if (pkg != null) outerPackages.add(pkg);
                out.putNextEntry(new ZipEntry(name));
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.closeEntry();
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        // Split-package guard: the inner mod (loaded onto GAME by this jar's own locator) must
        // not share a package with this game-library copy, also on GAME.
        Set<String> shared = collectNestedJarPackages(jar);
        shared.retainAll(outerPackages);
        if (!shared.isEmpty()) {
            Files.deleteIfExists(tmp);
            LOGGER.warn("[AutoModpack] {} shares package(s) {} between its outer jar and its nested mod, so it cannot be loaded in place without a split-package conflict. Add it to 'forceCopyFilesToStandardLocation' to load it from the standard mods directory instead.",
                    jar.getFileName(), shared);
            return null;
        }

        Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[AutoModpack] Built game-library copy of {} for in-place class/resource sharing", jar.getFileName());
        return copy;
    }

    /** Package (dotted) of a {@code .class} entry, or {@code null} for non-class / default-package / module-info. */
    private static String packageOfClassEntry(String entryName) {
        if (!entryName.endsWith(".class")) return null;
        if (entryName.endsWith("module-info.class") || entryName.endsWith("package-info.class")) return null;
        int slash = entryName.lastIndexOf('/');
        if (slash <= 0) return null;
        return entryName.substring(0, slash).replace('/', '.');
    }

    /** Class-file packages contained in this jar's {@code META-INF/jarjar} nested jars. */
    private static Set<String> collectNestedJarPackages(Path jar) {
        Set<String> packages = new HashSet<>();
        try (FileSystem fs = FileSystems.newFileSystem(jar)) {
            Path jarJarDir = fs.getPath("META-INF", "jarjar");
            if (Files.notExists(jarJarDir)) return packages;
            try (var stream = Files.newDirectoryStream(jarJarDir, "*.jar")) {
                for (Path nested : stream) {
                    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(nested))) {
                        ZipEntry entry;
                        while ((entry = zip.getNextEntry()) != null) {
                            String pkg = packageOfClassEntry(entry.getName());
                            if (pkg != null) packages.add(pkg);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[AutoModpack] Could not scan nested jars of {} for split-package check", jar.getFileName(), e);
        }
        return packages;
    }

    // The manifest is written by JarOutputStream itself; service declarations and the mod
    // metadata are dropped so the copy is a pure library, and nested jars are dropped so we
    // don't re-discover the inner mod twice. (A coremod's ICoreMod is run from the child SERVICE
    // layer via AutoModpackCoreMod, not from this GAME-layer copy - FML collects coremod
    // transformers before the GAME layer is built, so a GAME-layer ICoreMod is never scanned.)
    private static boolean shouldStripFromLibrary(String entryName) {
        return entryName.equalsIgnoreCase("META-INF/MANIFEST.MF")
                || entryName.equals("META-INF/neoforge.mods.toml")
                || entryName.equals("META-INF/mods.toml")
                || entryName.startsWith("META-INF/services/")
                || entryName.startsWith("META-INF/jarjar/");
    }

    private static Manifest readManifest(Path jar) {
        try (FileSystem fs = FileSystems.newFileSystem(jar)) {
            Path mf = fs.getPath("META-INF/MANIFEST.MF");
            if (Files.exists(mf)) {
                try (InputStream is = Files.newInputStream(mf)) {
                    return new Manifest(is);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[AutoModpack] Could not read manifest of {}, using a fresh one", jar.getFileName(), e);
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return manifest;
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
     * layer as its real self (loading normally); only non-standalone jars need the stripped copy.
     */
    public static boolean isStandaloneModFile(Path jar) {
        return info(jar).standalone();
    }

    /**
     * Instantiates the {@code ICoreMod}s shipped by the registered early-service jars (from their
     * child SERVICE layer, where the outer classes live) and collects their transformers. Called
     * by {@link AutoModpackCoreMod} during FML's {@code transformers()} pass.
     *
     * <p>This is how a modpack-folder coremod (e.g. Sinytra Connector, whose own mixins rely on
     * its coremod to remap their {@code @Shadow} targets) runs in place. FML only collects coremod
     * transformers from ICoreMods on layers built before the GAME layer; a modpack jar reaches at
     * best the GAME layer, so its own ICoreMod is never scanned. AutoModpack, however, sits on the
     * SERVICE layer and IS scanned - so it forwards the modpack coremods' transformers as its own.
     */
    public static List<ITransformer<?>> collectCoremodTransformers() {
        List<ITransformer<?>> transformers = new ArrayList<>();
        for (Map.Entry<Path, ClassLoader> entry : JAR_CLASSLOADERS.entrySet()) {
            Path jar = entry.getKey();
            if (!isCoremodJar(jar)) continue; // most early-service jars ship no coremod
            ClassLoader cl = entry.getValue();
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
     * prep). {@link GameGraphicsBootstrapTrigger} is a late fallback. Idempotent via
     * {@link #GAME_BRIDGE_DONE} - but the flag is claimed only once we actually hold the GAME
     * classloader, so a too-early call (a launch plugin's {@code addResources}) does not poison the
     * real one.
     *
     * <p>Coremod jars (e.g. Sinytra Connector) resolve their outer classes from a game-library copy
     * left intact and are skipped here.
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

        for (Map.Entry<Path, ClassLoader> entry : JAR_CLASSLOADERS.entrySet()) {
            Path jar = entry.getKey();
            ClassLoader childLoader = entry.getValue();
            // Coremod jars resolve their outer classes from a game-library copy left intact (see
            // EarlyModLocator/LazyModLocator); redirecting them is neither needed nor safe.
            if (isCoremodJar(jar) || !(childLoader instanceof cpw.mods.cl.ModuleClassLoader)) continue;

            try {
                int bridged = 0;
                for (String pkg : ModuleClassLoaderAccess.packageLookup(childLoader).keySet()) {
                    // Add the child route first (so there is never a window with neither route),
                    // then drop the copy's own mapping so future loads resolve to the child.
                    gameParentLoaders.put(pkg, childLoader);
                    gamePackageLookup.remove(pkg);
                    bridged++;
                }
                LOGGER.info("[AutoModpack] Bridged {} outer package(s) of {} to its early-service layer for in-place class sharing", bridged, jar.getFileName());
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
     * runs its own mod discovery and a {@code ForgeModPackageFilter} that strips its own packages
     * from every mod file it finds in the discovery set. So its game-library copy must NOT be
     * added during the candidate phase (it would be in that set and get gutted); {@link
     * LazyModLocator} adds it only after the coremod's dependency locator has finished running.
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
