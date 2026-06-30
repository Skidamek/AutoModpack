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
                    // Only jars whose every service we can host in place (skips e.g. coremods,
                    // which getWorkaroundMods sends down the copy-to-standard path instead).
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
        } catch (Throwable t) {
            // Never let this take the game down - mods we couldn't handle fall back to
            // the copy-to-standard workaround.
            LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
        }
    }

    /** Builds a child SERVICE layer for a single jar and fires its GraphicsBootstrappers. */
    private void bootstrapJar(Path jar, ModuleLayer serviceLayer, String[] arguments) {
        ClassLoader serviceClassLoader;
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
            ModuleLayer.defineModules(configuration, List.of(serviceLayer), name -> classLoader);

            serviceClassLoader = classLoader;
        } catch (Throwable t) {
            // Couldn't isolate this jar onto a service layer - leave it for the
            // copy-to-standard fallback (we don't mark it handled).
            LOGGER.error("[AutoModpack] Could not build a service layer for {}, falling back to copy-to-standard", jar.getFileName(), t);
            return;
        }

        // Register before firing so the locator phase can find the classloader even if a
        // bootstrapper throws.
        EarlyServiceLayer.register(jar, serviceClassLoader);

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
