package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.HashUtils;

import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static pl.skidam.automodpack_core.Constants.*;

/**
 * A {@link GraphicsBootstrapper} shipped by AutoModpack.
 *
 * <p>AutoModpack itself is on the loader's SERVICE layer (it provides an
 * {@code IModFileCandidateLocator}), so the loader invokes this bootstrapper during the
 * early-window phase - the exact moment neo/forge fires every other mod's
 * {@code GraphicsBootstrapper}. Mods placed only in AutoModpack's modpack folder never
 * reach that phase on their own, which is why mods like Ixeris (which transforms
 * {@code org.lwjgl.glfw.GLFW} here) or Sodium froze/crashed unless they were copied into
 * the standard {@code mods/} directory.
 *
 * <p>This bootstrapper finds the early-service jars in the selected modpack folder,
 * builds a correctly-named child SERVICE module layer per jar (so each mod's bootstrapper
 * runs in the classloader context it expects), and fires their {@code GraphicsBootstrapper}s
 * in place. {@link EarlyModLocator}/{@link LazyModLocator} then replay the same jars'
 * mod-locating services so the actual mods load - no copy, no restart.
 */
@SuppressWarnings("unused")
public class EarlyServiceBootstrapper implements GraphicsBootstrapper {

    @Override
    public String name() {
        return "automodpack";
    }

    @Override
    public void bootstrap(String[] arguments) {
        try {
            Path gameDir = gameDir(arguments);

            Path modpackMods = resolveSelectedModpackMods(gameDir);
            if (modpackMods == null || !Files.isDirectory(modpackMods)) {
                return;
            }

            // Jars already present in the standard mods directory are picked up by the
            // loader's own early discovery (and would be on the real SERVICE layer), so
            // skip those to avoid firing their bootstrappers twice.
            Set<String> standardModHashes = hashStandardMods(gameDir);

            List<Path> earlyServiceJars = new ArrayList<>();
            try (Stream<Path> stream = Files.list(modpackMods)) {
                for (Path jar : stream.filter(EarlyServiceBootstrapper::isJar).toList()) {
                    String hash = HashUtils.getHash(jar);
                    if (hash != null && standardModHashes.contains(hash)) {
                        continue;
                    }
                    // Only jars whose every service we can host in place - including coremods
                    // (Connector). A jar shipping any service we can't host (e.g. IModFileReader)
                    // is left for getWorkaroundMods' copy-to-standard path instead.
                    if (EarlyServiceLayer.eligibleForInPlace(jar)) {
                        earlyServiceJars.add(jar);
                    }
                }
            }

            if (earlyServiceJars.isEmpty()) {
                return;
            }

            LOGGER.info("[AutoModpack] Bootstrapping {} early-service mod(s) from the modpack folder in place", earlyServiceJars.size());

            ModuleLayer serviceLayer = getClass().getModule().getLayer();
            if (serviceLayer == null) {
                LOGGER.warn("[AutoModpack] Not running on a module layer, cannot bootstrap early services in place");
                return;
            }

            for (Path jar : earlyServiceJars) {
                bootstrapJar(jar, serviceLayer, arguments);
            }

            // We fire here at the correct early-window phase, but the in-place outer classes only
            // get single-class identity once the GAME classloader is bridged to the child layers,
            // and that must happen before Mixin prepares its configs (which loads those outer
            // classes). Our ILaunchPluginService does the bridge from its initializeLaunch, but
            // ModLauncher stores launch plugins in an unordered map and may run Mixin's before ours.
            // Reorder ours first now - the plugins are already discovered at this phase.
            EarlyServiceBridgePlugin.ensureRunsFirst();
        } catch (Throwable t) {
            // Never let this take the game down - mods we couldn't handle fall back to
            // the copy-to-standard workaround.
            LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
        }
    }

    /**
     * Builds the child SERVICE layer for a single jar, fires its {@code GraphicsBootstrapper}s on
     * that layer at this (correct) early-window phase, and registers its classloader.
     *
     * <p>The child layer is also what {@link EarlyModLocator}/{@link LazyModLocator} run the jar's
     * candidate/dependency locators on, and {@link AutoModpackCoreMod} its coremod, during the
     * pre-GAME discovery phase.
     *
     * <p>Firing here is exactly NeoForge's timing. The class identity it initialises is reconciled
     * with the GAME layer afterwards: instead of giving GAME a separate copy of the outer jar (the
     * split that crashed asynclogger - GAME read an uninitialised second copy), {@link
     * EarlyServiceLayer#bridgeEarlyServicesToGameLayer()} points the GAME classloader at <em>this</em>
     * child layer for the outer packages, so the mod reads the very class fired here.
     */
    private void bootstrapJar(Path jar, ModuleLayer serviceLayer, String[] arguments) {
        ClassLoader serviceClassLoader;
        ModuleLayer childLayer;
        try {
            SecureJar secureJar = SecureJar.from(jar);
            String moduleName = secureJar.name();

            Configuration configuration = serviceLayer.configuration()
                    .resolveAndBind(JarModuleFinder.of(secureJar), ModuleFinder.of(), List.of(moduleName));

            // The child classloader must be able to delegate to every module its mod reads
            // (GraphicsBootstrapper, the locating APIs, etc.), which live on the SERVICE
            // layer AND its ancestors (BOOT). ModuleClassLoader only consults the parent
            // layers it is given directly, so pass the full flattened ancestor list -
            // exactly like ModLauncher does when it builds the SERVICE layer.
            List<ModuleLayer> parentLayers = flattenParents(serviceLayer);

            // The classloader name must be one the bootstrapped mods accept (e.g. Ixeris
            // verifies it is "LAYER SERVICE", "TRANSFORMER" or "FML Early Services").
            ModuleClassLoader classLoader = new ModuleClassLoader("FML Early Services", configuration, parentLayers);
            classLoader.setFallbackClassLoader(getClass().getClassLoader());
            // Keep the child layer: the GAME-layer bridge needs its module(s) to wire up JPMS read
            // edges (game <-> child), not just the classloader routing.
            childLayer = ModuleLayer.defineModules(configuration, List.of(serviceLayer), name -> classLoader).layer();

            serviceClassLoader = classLoader;
        } catch (Throwable t) {
            // Couldn't isolate this jar onto a service layer - leave it for the
            // copy-to-standard fallback (we don't mark it handled).
            LOGGER.error("[AutoModpack] Could not build a service layer for {}, falling back to copy-to-standard", jar.getFileName(), t);
            return;
        }

        // Register before firing so the locator phase (and the GAME-layer bridge) can find the
        // classloader even if a bootstrapper throws.
        EarlyServiceLayer.register(jar, serviceClassLoader, childLayer);

        for (String impl : EarlyServiceLayer.serviceImpls(jar, EarlyServiceLayer.GRAPHICS_BOOTSTRAPPER_SERVICE)) {
            try {
                GraphicsBootstrapper bootstrapper = (GraphicsBootstrapper) Class.forName(impl, true, serviceClassLoader)
                        .getDeclaredConstructor().newInstance();
                LOGGER.info("[AutoModpack] Invoking in-place GraphicsBootstrapper {} ({}) from {}", impl, bootstrapper.name(), jar.getFileName());
                bootstrapper.bootstrap(arguments);
            } catch (Throwable t) {
                LOGGER.error("[AutoModpack] In-place GraphicsBootstrapper {} from {} failed", impl, jar.getFileName(), t);
            }
        }
    }

    /** A layer plus all of its ancestors, deduplicated (closest layers first). */
    private static List<ModuleLayer> flattenParents(ModuleLayer layer) {
        List<ModuleLayer> result = new ArrayList<>();
        java.util.Deque<ModuleLayer> queue = new java.util.ArrayDeque<>();
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

        // Unofficial builds may bake the client config into the jar - honour that first.
        try (InputStream is = getClass().getResourceAsStream("/" + clientConfigFileOverrideResource)) {
            if (is != null) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                Jsons.ClientConfigFieldsV2 config = ConfigTools.load(json, Jsons.ClientConfigFieldsV2.class);
                if (config != null) selected = config.selectedModpack;
            }
        } catch (Exception ignored) {
            // fall through to the on-disk config
        }

        if (selected == null) {
            Jsons.ClientConfigFieldsV2 config = ConfigTools.load(gameDir.resolve(clientConfigFile), Jsons.ClientConfigFieldsV2.class);
            if (config != null) selected = config.selectedModpack;
        }

        if (selected == null || selected.isBlank()) {
            return null;
        }

        return gameDir.resolve(modpacksDir).resolve(selected).resolve("mods");
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
            LOGGER.debug("[AutoModpack] Failed to list standard mods directory while bootstrapping early services", e);
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
}
