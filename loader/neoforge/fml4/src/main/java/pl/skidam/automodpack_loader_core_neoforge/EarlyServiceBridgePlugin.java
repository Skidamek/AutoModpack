package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static pl.skidam.automodpack_core.Constants.LOGGER;

/**
 * A ModLauncher launch plugin shipped by AutoModpack whose only job is its <em>timing</em>: it
 * transforms no class ({@link #handlesClass} is empty), but ModLauncher calls every launch
 * plugin's {@link #initializeLaunch} during {@code announceLaunch} - when the GAME layer's
 * {@code TransformingClassLoader} exists but <em>before</em> the game main runs, i.e. before Mixin
 * prepares its configs and loads the early-service jars' outer classes.
 *
 * <p>That is exactly the window the GAME-layer bridge needs (see
 * {@link EarlyServiceLayer#bridgeEarlyServicesToGameLayer()}): pointing the GAME classloader at the
 * child layers here, before any outer class loads, means every outer class - whether referenced
 * structurally during Mixin config prep (Sodium) or only at runtime (asynclogger) - resolves to the
 * single, already-bootstrapped child-layer copy. No GAME-library copy, no split static state.
 *
 * <p>ModLauncher only discovers launch plugins from the BOOT module layer, while AutoModpack ships on
 * the SERVICE layer, so a {@code META-INF/services} entry would never be picked up - we don't ship one.
 * Instead we inject this plugin into ModLauncher's launch-plugin map by reflection - see
 * {@link #ensureRunsFirst()}.
 */
@SuppressWarnings("unused")
public class EarlyServiceBridgePlugin implements ILaunchPluginService {

    static final String NAME = "automodpack_early_service_bridge";

    /**
     * Registers this launch plugin with ModLauncher and makes it run first.
     *
     * <p>ModLauncher discovers {@link ILaunchPluginService}s only from the BOOT module layer
     * ({@code LaunchPluginHandler}'s constructor does {@code ServiceLoader.load(BOOT, ...)}).
     * AutoModpack ships on the SERVICE layer, so a {@code META-INF/services} entry would never be
     * seen - confirmed empirically: the launch-plugin map only ever holds NeoForge's built-in BOOT
     * plugins (mixin, slf4jfixer, ...) and never ours. So we inject our own instance into that map by
     * reflection, from our {@code GraphicsBootstrapper} (the early-window phase, well before the
     * launch). {@code announceLaunch} then calls our {@link #initializeLaunch} - which runs the
     * GAME-layer bridge - after the GAME {@code TransformingClassLoader} is built but before the game
     * main runs and any mod class (e.g. Sodium's {@code Workarounds}) is loaded.
     *
     * <p>We install a {@link PriorityFirstPluginMap} so our callback also runs ahead of every other
     * plugin's (notably Mixin's config prep), in case any of them structurally load an early-service
     * jar's outer class during {@code announceLaunch} itself. Idempotent.
     */
    static void ensureRunsFirst() {
        try {
            // Plain reflection is enough here: unlike cpw.mods.securejarhandler (which seals
            // cpw.mods.cl, forcing the GAME-layer bridge onto Unsafe), the cpw.mods.modlauncher
            // module is reachable, so we can read/write its fields with setAccessible.
            Object launcher = Class.forName("cpw.mods.modlauncher.Launcher").getField("INSTANCE").get(null);
            if (launcher == null) return;
            Object pluginHandler = readField(launcher, "launchPlugins");
            if (pluginHandler == null) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> plugins = (Map<String, Object>) readField(pluginHandler, "plugins");
            if (plugins == null || plugins instanceof PriorityFirstPluginMap) return;
            PriorityFirstPluginMap ordered = new PriorityFirstPluginMap(NAME, plugins);
            ordered.putIfAbsent(NAME, new EarlyServiceBridgePlugin());
            writeField(pluginHandler, "plugins", ordered);
            LOGGER.info("[AutoModpack] Injected the early-service bridge as ModLauncher launch plugin '{}' and ordered it first (ModLauncher discovers launch plugins only from the BOOT layer, so a SERVICE-layer service file would never be seen)", NAME);
        } catch (Throwable t) {
            LOGGER.warn("[AutoModpack] Could not inject the early-service bridge launch plugin; in-place graphics-service mods may fail", t);
        }
    }

    private static Object readField(Object owner, String name) throws Exception {
        Field field = findField(owner.getClass(), name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void writeField(Object owner, String name, Object value) throws Exception {
        Field field = findField(owner.getClass(), name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static Field findField(Class<?> from, String name) throws NoSuchFieldException {
        for (Class<?> c = from; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // try superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * A launch-plugin map that always iterates {@code firstKey} first. ModLauncher reads the same
     * {@code plugins} field for {@code offerScanResultsToPlugins} (addResources) and
     * {@code announceLaunch} (initializeLaunch), both via {@code Map.forEach}, so overriding
     * {@code forEach} is enough to make our bridge run before any other plugin's callback. The
     * priority key is resolved at iteration time, so it holds however and whenever our entry is added.
     */
    private static final class PriorityFirstPluginMap extends LinkedHashMap<String, Object> {
        private final String firstKey;

        PriorityFirstPluginMap(String firstKey, Map<String, Object> initial) {
            super(initial);
            this.firstKey = firstKey;
        }

        @Override
        public void forEach(BiConsumer<? super String, ? super Object> action) {
            Object first = get(firstKey);
            if (first != null) action.accept(firstKey, first);
            super.forEach((key, value) -> {
                if (!firstKey.equals(key)) action.accept(key, value);
            });
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        // We never transform a class; this plugin exists purely for the initializeLaunch callback.
        return EnumSet.noneOf(Phase.class);
    }

    @Override
    public void addResources(List<SecureJar> resources) {
        // Runs during offerScanResultsToPlugins, before the GAME TransformingClassLoader is built -
        // too early to bridge. We only (re)assert our ordering here; the bridge happens in
        // initializeLaunch, during announceLaunch, once the GAME loader exists.
        ensureRunsFirst();
    }

    @Override
    public void initializeLaunch(ITransformerLoader transformerLoader, NamedPath[] specialPaths) {
        // announceLaunch: the GAME TransformingClassLoader is built (and is the thread context loader)
        // but the game main has not run, so no mod class has loaded yet. Bridge now, before Mixin
        // prepares its configs and loads the early-service jars' outer classes.
        EarlyServiceLayer.bridgeEarlyServicesToGameLayer();
    }
}
