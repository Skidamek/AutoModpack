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

            Set<String> standardModHashes = hashStandardMods(gameDir);

            List<Path> earlyServiceJars = new ArrayList<>();
            try (Stream<Path> stream = Files.list(modpackMods)) {
                for (Path jar : stream.filter(EarlyServiceBootstrapper::isJar).toList()) {
                    String hash = HashUtils.getHash(jar);
                    if (hash != null && standardModHashes.contains(hash)) {
                        continue;
                    }
                    if (EarlyServiceLayer.eligibleForInPlace(jar)) {
                        earlyServiceJars.add(jar);
                    }
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

            for (Path jar : earlyServiceJars) {
                bootstrapJar(jar, serviceLayer, arguments);
            }

            EarlyServiceLayer.runTransformationServiceOnLoad();
            EarlyServiceBridgePlugin.ensureRunsFirst();
        } catch (Throwable t) {
            Constants.LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
        }
    }

    private void bootstrapJar(Path jar, ModuleLayer serviceLayer, String[] arguments) {
        ClassLoader serviceClassLoader;
        ModuleLayer childLayer;
        try {
            SecureJar secureJar = SecureJar.from(jar);
            String moduleName = secureJar.name();

            Configuration configuration = serviceLayer.configuration()
                    .resolveAndBind(JarModuleFinder.of(secureJar), ModuleFinder.of(), List.of(moduleName));

            List<ModuleLayer> parentLayers = flattenParents(serviceLayer);

            ModuleClassLoader classLoader = new ModuleClassLoader("FML Early Services", configuration, parentLayers);
            classLoader.setFallbackClassLoader(getClass().getClassLoader());
            childLayer = ModuleLayer.defineModules(configuration, List.of(serviceLayer), name -> classLoader).layer();

            serviceClassLoader = classLoader;
        } catch (Throwable t) {
            Constants.LOGGER.error("[AutoModpack] Could not build a service layer for {}, falling back to copy-to-standard", jar.getFileName(), t);
            return;
        }

        EarlyServiceLayer.register(jar, serviceClassLoader, childLayer);

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

        try (InputStream is = getClass().getResourceAsStream("/overrides-automodpack-client.json")) {
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
}
