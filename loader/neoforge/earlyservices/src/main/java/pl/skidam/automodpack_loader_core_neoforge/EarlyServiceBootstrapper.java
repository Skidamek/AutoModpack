package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.HashUtils;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

            ClassLoader childLoader = appendToFmlClassLoaderChain(earlyServiceJars);
            if (childLoader == null) {
                return; // logged inside
            }

            EarlyServiceLayer.register(earlyServiceJars, childLoader);

            for (Path jar : earlyServiceJars) {
                for (String impl : EarlyServiceLayer.serviceImpls(jar, EarlyServiceLayer.GRAPHICS_BOOTSTRAPPER_SERVICE)) {
                    try {
                        GraphicsBootstrapper bootstrapper = (GraphicsBootstrapper) Class.forName(impl, true, childLoader)
                                .getDeclaredConstructor().newInstance();
                        Constants.LOGGER.info("[AutoModpack] Invoking in-place GraphicsBootstrapper {} ({}) from {}", impl, bootstrapper.name(), jar.getFileName());
                        bootstrapper.bootstrap(arguments);
                    } catch (Throwable t) {
                        Constants.LOGGER.error("[AutoModpack] In-place GraphicsBootstrapper {} from {} failed", impl, jar.getFileName(), t);
                    }
                }
            }
        } catch (Throwable t) {
            Constants.LOGGER.error("[AutoModpack] Early-service bootstrap failed", t);
        }
    }

    /**
     * Grows FMLLoader's own flat classloader chain with these jars, mirroring exactly what it does
     * for its own "FML Early Services" jars ({@code FMLLoader.loadEarlyServices()} ->
     * {@code appendLoader("FML Early Services", jarContentsList)}) - a private instance method, so
     * reflection is needed, but {@code fmlloader.jar} ships no {@code module-info} (early-service
     * classes, ours included, run in the unnamed module at this phase), so plain
     * {@code setAccessible} reaches it; no {@code Unsafe} needed, unlike the old {@code cpw.mods.cl}
     * module boundary on NeoForge <21.6.
     *
     * <p>This is also the entire "bridge to the game layer": {@code FMLLoader} later builds the GAME
     * {@code TransformingClassLoader} with {@code setFallbackClassLoader(currentClassLoader)} - since
     * we grow that same {@code currentClassLoader} chain here, before the game loader is built, game
     * code that references this jar's outer classes resolves them through that native fallback with
     * no further action from us.
     */
    private ClassLoader appendToFmlClassLoaderChain(List<Path> jars) {
        try {
            Class<?> fmlLoaderClass = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Object current = fmlLoaderClass.getMethod("getCurrent").invoke(null);

            Class<?> jarContentsClass = Class.forName("net.neoforged.fml.jarcontents.JarContents");
            Method ofPath = jarContentsClass.getMethod("ofPath", Path.class);
            List<Object> jarContentsList = new ArrayList<>(jars.size());
            for (Path jar : jars) {
                jarContentsList.add(ofPath.invoke(null, jar));
            }

            Method appendLoader = fmlLoaderClass.getDeclaredMethod("appendLoader", String.class, List.class);
            appendLoader.setAccessible(true);
            appendLoader.invoke(current, "automodpack modpack early services", jarContentsList);

            Method getCurrentClassLoader = fmlLoaderClass.getMethod("getCurrentClassLoader");
            return (ClassLoader) getCurrentClassLoader.invoke(current);
        } catch (Throwable t) {
            Constants.LOGGER.error("[AutoModpack] Could not append the early-service jars to FMLLoader's classloader chain; none of them will be bootstrapped in place", t);
            return null;
        }
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
}
