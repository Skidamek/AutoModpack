package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.utils.EarlyServiceScan;
import pl.skidam.automodpack_loader_core_modlauncher.EarlyServiceBridgePlugin;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

public class EarlyServiceBootstrapper implements GraphicsBootstrapper {

    public static volatile String EARLY_MC_VERSION;
    public static volatile String EARLY_NEOFORGE_VERSION;
    // FMLLoader.getDist() is also unreliable this early - confirmed live to be the actual cause of
    // a regression with Sinytra Connector: LoaderManager.getEnvironmentType() read SERVER on a
    // CLIENT launch during Preload, so Preload.updateAll() took its dedicated-server-only branch
    // and returned without ever populating ModpackLoader.modsToLoad, silently skipping Connector's
    // embedded Fabric-adapter mod jar (added only via EarlyModLocator's replay of that list) - not
    // any timing-sensitivity in Connector itself. NeoForge's launchTarget naming convention (e.g.
    // "forgeclient"/"forgeserver") reliably encodes dist and is on the command line here.
    public static volatile Boolean EARLY_IS_CLIENT;

    @Override
    public String name() {
        return "automodpack";
    }

    @Override
    public void bootstrap(String[] arguments) {
        try {
            EARLY_MC_VERSION = argValue(arguments, "--fml.mcVersion");
            EARLY_NEOFORGE_VERSION = argValue(arguments, "--fml.neoForgeVersion");
            String launchTarget = argValue(arguments, "--launchTarget");
            if (launchTarget != null) {
                EARLY_IS_CLIENT = !launchTarget.toLowerCase(java.util.Locale.ROOT).contains("server");
            }

            // Run our own update/reconcile step FIRST, before anything below reads the modpack
            // folder - this is the earliest hook NeoForge gives any mod (confirmed live: it fires
            // ~50ms before ModLauncher's own ITransformationService.onLoad lifecycle). Doing the
            // update here, rather than later in EarlyModLocator, means an update that changes which
            // mods are early-service mods is already reflected in the folder we scan below, in the
            // same boot - no restart needed. It also loads the config and publishes
            // Constants.selectedModpackDir / Constants.MODS_DIR, which everything below reads.
            net.neoforged.fml.loading.progress.ProgressMeter progress =
                    net.neoforged.fml.loading.progress.StartupNotificationManager.prependProgressBar("[Automodpack] Preload", 0);
            new pl.skidam.automodpack_loader_core.Preload();
            progress.complete();

            // Set by Preload only when a modpack is selected on a client - null means nothing to do.
            Path modpackMods = Constants.selectedModpackDir == null ? null : Constants.selectedModpackDir.resolve("mods");
            if (modpackMods == null || !Files.isDirectory(modpackMods)) {
                return;
            }

            List<Path> earlyServiceJars = EarlyServiceScan.eligibleJars(modpackMods, EarlyServiceLayer::eligibleForInPlace);

            if (earlyServiceJars.isEmpty()) {
                return;
            }

            Constants.LOGGER.info("[AutoModpack] Bootstrapping {} early-service mod(s) from the modpack folder in place", earlyServiceJars.size());

            ModuleLayer serviceLayer = getClass().getModule().getLayer();
            if (serviceLayer == null) {
                Constants.LOGGER.warn("[AutoModpack] Not running on a module layer, cannot bootstrap early services in place");
                return;
            }

            bootstrapJars(earlyServiceJars, serviceLayer, arguments);

            EarlyServiceLayer.instantiateTransformationServices();
            EarlyServiceBridgePlugin.ensureRunsFirst(EarlyServiceLayer::bridgeEarlyServicesToGameLayer);
        } catch (Throwable t) {
            Constants.LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
        }
    }

    /**
     * Resolves every eligible jar into ONE shared child configuration/layer/classloader - mirroring
     * {@code ModuleLayerHandler.buildLayer}, which resolves every jar destined for a given layer (e.g.
     * the loader's own SERVICE layer) together in a single {@code Configuration.resolveAndBind} call.
     * Building one configuration per jar (sibling layers) would mean one early-service jar's module can
     * never {@code requires}/classload another's - breaking any modpack-folder mod that is split across,
     * or depends on, more than one early-service jar. Resolving them together lets such edges resolve
     * exactly as they would if these jars sat together on the loader's real SERVICE layer.
     *
     * <p>(A live regression with Sinytra Connector was previously misattributed to this method; the
     * actual cause was {@code ITransformationService.initialize()} running too early relative to
     * NeoForge's window-provider assignment - see {@link EarlyModLocator#findCandidates}. This shared
     * layer is unrelated to that timing and does not need to be avoided.)
     */
    private void bootstrapJars(List<Path> jars, ModuleLayer serviceLayer, String[] arguments) {
        List<Path> registered = new ArrayList<>(jars);
        if (!buildAndRegister(jars, serviceLayer)) {
            // The shared resolution failed (e.g. two jars deriving the same automatic module name
            // throw a ResolutionException for the whole batch). Retry each jar on its own layer so
            // one bad jar doesn't take every other early-service mod down with it - cross-jar
            // `requires` edges are lost in this degraded mode, but only the jars that actually fail
            // resolution stay unregistered.
            registered.clear();
            for (Path jar : jars) {
                if (buildAndRegister(List.of(jar), serviceLayer)) registered.add(jar);
            }
        }

        for (Path jar : registered) {
            for (String impl : EarlyServiceLayer.serviceImpls(jar, EarlyServiceLayer.GRAPHICS_BOOTSTRAPPER_SERVICE)) {
                try {
                    GraphicsBootstrapper bootstrapper = (GraphicsBootstrapper) Class.forName(impl, true, EarlyServiceLayer.classLoaderFor(jar))
                            .getDeclaredConstructor().newInstance();
                    Constants.LOGGER.info("[AutoModpack] Invoking in-place GraphicsBootstrapper {} ({}) from {}", impl, bootstrapper.name(), jar.getFileName());
                    bootstrapper.bootstrap(arguments);
                } catch (Throwable t) {
                    Constants.LOGGER.error("[AutoModpack] In-place GraphicsBootstrapper {} from {} failed", impl, jar.getFileName(), t);
                }
            }
        }
    }

    /**
     * Resolves the given jars into one child configuration/layer/classloader and registers each with
     * {@link EarlyServiceLayer}. Returns false - with nothing registered - if resolution fails.
     */
    private boolean buildAndRegister(List<Path> jars, ModuleLayer serviceLayer) {
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

            ModuleClassLoader classLoader = new ModuleClassLoader("FML Early Services", configuration, parentLayers);
            classLoader.setFallbackClassLoader(getClass().getClassLoader());
            ModuleLayer childLayer = ModuleLayer.defineModules(configuration, List.of(serviceLayer), name -> classLoader).layer();

            for (int i = 0; i < jars.size(); i++) {
                EarlyServiceLayer.register(jars.get(i), classLoader, childLayer, moduleNames.get(i));
            }
            return true;
        } catch (Throwable t) {
            Constants.LOGGER.error("[AutoModpack] Could not build a service layer for early-service jar(s) {}", jars.stream().map(Path::getFileName).toList(), t);
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


    private static String argValue(String[] arguments, String name) {
        if (arguments != null) {
            String prefix = name + "=";
            for (int i = 0; i < arguments.length; i++) {
                if (name.equals(arguments[i]) && i + 1 < arguments.length) {
                    return arguments[i + 1];
                }
                if (arguments[i].startsWith(prefix)) {
                    return arguments[i].substring(prefix.length());
                }
            }
        }
        return null;
    }
}
