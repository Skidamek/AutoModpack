package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.utils.EarlyServiceScan;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class EarlyServiceBootstrapper implements GraphicsBootstrapper {

    // FMLLoader.getCurrent().getVersionInfo() is still null this early (confirmed live on fml4,
    // and setupLaunchHandler runs at the same relative point on this loader generation), so
    // LoaderManager can't get the MC/loader version from it during Preload's initializeConstants()
    // the way it safely could when Preload ran later, from EarlyModLocator. ModLauncher already
    // has both on the command line (`--fml.mcVersion 1.21.10 --fml.neoForgeVersion ...`), so
    // capture them from here - the one place with access to `arguments` - before Preload runs.
    public static volatile String EARLY_MC_VERSION;
    public static volatile String EARLY_NEOFORGE_VERSION;
    // FMLLoader.getCurrent().getDist() is also unreliable this early - confirmed live on fml4 to be
    // the actual cause of a regression: reading it too early can silently return SERVER on a
    // genuine CLIENT launch, so Preload.updateAll() takes its dedicated-server-only branch and never
    // populates the mod list early-service discovery depends on. NeoForge's launchTarget naming
    // convention (e.g. "forgeclient"/"forgeserver") reliably encodes dist and is on the same
    // arguments array as the version args below.
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
            // folder - GraphicsBootstrapper is the earliest hook this loader generation gives any
            // mod (there is no ModLauncher/ITransformationService concept on fml10/fml11 to compete
            // with it). Doing the update here, rather than later in EarlyModLocator, means an update
            // that changes which mods are early-service mods is already reflected in the folder we
            // scan below, in the same boot - no restart needed. NeoForge fml4 does the same from its
            // own GraphicsBootstrapper (a separate implementation, since it still has a competing
            // ITransformationService.onLoad phase and its own module-layer bridge). It also loads
            // the config and publishes Constants.selectedModpackDir / Constants.MODS_DIR, which
            // everything below reads.
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

            ClassLoader childLoader = appendToFmlClassLoaderChain(earlyServiceJars);
            if (childLoader == null) {
                // The batched append failed (logged inside) - e.g. one unreadable jar poisoning the
                // whole JarContents list. Retry each jar on its own so only the jars that actually
                // fail stay out; each append parents onto the current chain, so the last returned
                // loader resolves every successfully appended jar.
                List<Path> appended = new ArrayList<>();
                for (Path jar : earlyServiceJars) {
                    ClassLoader loader = appendToFmlClassLoaderChain(List.of(jar));
                    if (loader != null) {
                        childLoader = loader;
                        appended.add(jar);
                    }
                }
                earlyServiceJars = appended;
                if (earlyServiceJars.isEmpty()) {
                    return;
                }
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
            Constants.LOGGER.error("[AutoModpack] Could not append early-service jar(s) {} to FMLLoader's classloader chain", jars.stream().map(Path::getFileName).toList(), t);
            return null;
        }
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
