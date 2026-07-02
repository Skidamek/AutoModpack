package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.HashUtils;

import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

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
            Path gameDir = gameDir(arguments);
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
            // same boot - no restart needed.
            net.neoforged.fml.loading.progress.ProgressMeter progress =
                    net.neoforged.fml.loading.progress.StartupNotificationManager.prependProgressBar("[Automodpack] Preload", 0);
            new pl.skidam.automodpack_loader_core.Preload();
            progress.complete();

            Path modpackMods = resolveSelectedModpackMods(gameDir);
            if (modpackMods == null || !Files.isDirectory(modpackMods)) {
                return;
            }

            // Checking eligibility first (a handful of root-level Files.exists checks on an already-open
            // FileSystem) before hashing (a full-content SHA-1 read) avoids paying for a hash on every
            // ordinary, non-early-service mod - most modpack jars never need one. standardModHashes is
            // itself a full hash of every standard-mods/ jar, so it is computed lazily too, only once
            // an eligible jar actually needs the comparison.
            List<Path> earlyServiceJars = new ArrayList<>();
            Set<String> standardModHashes = null;
            try (Stream<Path> stream = Files.list(modpackMods)) {
                for (Path jar : stream.filter(EarlyServiceBootstrapper::isJar).toList()) {
                    if (!EarlyServiceLayer.eligibleForInPlace(jar)) {
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

            ModuleLayer serviceLayer = getClass().getModule().getLayer();
            if (serviceLayer == null) {
                Constants.LOGGER.warn("[AutoModpack] Not running on a module layer, cannot bootstrap early services in place");
                return;
            }

            bootstrapJars(earlyServiceJars, serviceLayer, arguments);

            EarlyServiceLayer.instantiateTransformationServices();
            EarlyServiceBridgePlugin.ensureRunsFirst();
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
        ClassLoader serviceClassLoader;
        ModuleLayer childLayer;
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
            childLayer = ModuleLayer.defineModules(configuration, List.of(serviceLayer), name -> classLoader).layer();

            serviceClassLoader = classLoader;
        } catch (Throwable t) {
            Constants.LOGGER.error("[AutoModpack] Could not build a shared service layer for the early-service mods; none of them will be bootstrapped in place", t);
            return;
        }

        for (Path jar : jars) {
            EarlyServiceLayer.register(jar, serviceClassLoader, childLayer);
        }

        for (Path jar : jars) {
            for (String impl : EarlyServiceLayer.serviceImpls(jar, EarlyServiceLayer.GRAPHICS_BOOTSTRAPPER_SERVICE)) {
                try {
                    GraphicsBootstrapper bootstrapper = (GraphicsBootstrapper) Class.forName(impl, true, serviceClassLoader)
                            .getDeclaredConstructor().newInstance();
                    Constants.LOGGER.info("[AutoModpack] Invoking in-place GraphicsBootstrapper {} ({}) from {}", impl, bootstrapper.name(), jar.getFileName());
                    bootstrapper.bootstrap(arguments);
                } catch (Throwable t) {
                    Constants.LOGGER.error("[AutoModpack] In-place GraphicsBootstrapper {} from {} failed", impl, jar.getFileName(), t);
                }
            }
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

    private Path resolveSelectedModpackMods(Path gameDir) {
        String selected = null;

        try (InputStream is = getClass().getResourceAsStream("/" + Constants.clientConfigFileOverrideResource)) {
            if (is != null) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                Jsons.ClientConfigFieldsV2 config = ConfigTools.load(json, Jsons.ClientConfigFieldsV2.class);
                if (config != null) selected = config.selectedModpack;
            }
        } catch (Exception ignored) {
        }

        if (selected == null) {
            Jsons.ClientConfigFieldsV2 config = ConfigTools.load(gameDir.resolve(Constants.clientConfigFile), Jsons.ClientConfigFieldsV2.class);
            if (config != null) selected = config.selectedModpack;
        }

        if (selected == null || selected.isBlank()) {
            return null;
        }

        return gameDir.resolve(Constants.modpacksDir).resolve(selected).resolve("mods");
    }

    private Set<String> hashStandardMods(Path gameDir) {
        Set<String> hashes = new HashSet<>();
        Path modsDir = gameDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return hashes;
        }
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(EarlyServiceBootstrapper::isJar).forEach(jar -> {
                String hash = HashUtils.getHash(jar);
                if (hash != null) hashes.add(hash);
            });
        } catch (Exception e) {
            Constants.LOGGER.debug("[AutoModpack] Failed to list standard mods directory while bootstrapping early services", e);
        }
        return hashes;
    }

    private static Path gameDir(String[] arguments) {
        if (arguments != null) {
            for (int i = 0; i < arguments.length - 1; i++) {
                if ("--gameDir".equals(arguments[i])) {
                    return Path.of(arguments[i + 1]).toAbsolutePath().normalize();
                }
            }
        }
        return Path.of(".").toAbsolutePath().normalize();
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
