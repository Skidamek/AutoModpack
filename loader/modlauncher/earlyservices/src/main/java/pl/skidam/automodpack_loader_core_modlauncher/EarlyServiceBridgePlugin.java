package pl.skidam.automodpack_loader_core_modlauncher;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.objectweb.asm.Type;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import pl.skidam.automodpack_core.Constants;

public class EarlyServiceBridgePlugin implements ILaunchPluginService {

    static final String NAME = "automodpack_early_service_bridge";

    // Forge and NeoForge fml4 each have their own EarlyServiceLayer (different SPI types), so the
    // actual bridging call is supplied by the caller rather than named here.
    private static volatile Runnable onInitializeLaunch;

    @Override
    public String name() {
        return NAME;
    }

    public static void ensureRunsFirst(Runnable bridgeEarlyServicesToGameLayer) {
        onInitializeLaunch = bridgeEarlyServicesToGameLayer;
        ensureRunsFirst();
    }

    private static void ensureRunsFirst() {
        try {
            Object launcher = ModuleClassLoaderAccess.launcherInstance();
            if (launcher == null) return;
            Object pluginHandler = ModuleClassLoaderAccess.readField(launcher, "launchPlugins");
            if (pluginHandler == null) return;
            Map<String, Object> plugins = (Map<String, Object>) ModuleClassLoaderAccess.readField(pluginHandler, "plugins");
            if (plugins == null) return;
            if (plugins instanceof PriorityPluginMap ordered) {
                ordered.putIfAbsent(NAME, new EarlyServiceBridgePlugin());
                return;
            }
            PriorityPluginMap ordered = new PriorityPluginMap(NAME, plugins);
            ordered.putIfAbsent(NAME, new EarlyServiceBridgePlugin());
            ModuleClassLoaderAccess.writeField(pluginHandler, "plugins", ordered);
            Constants.LOGGER.info("[AutoModpack] Injected early-service bridge launch plugin '{}' (ModLauncher discovers launch plugins only from the BOOT layer, so SERVICE-layer service files would never be seen)", NAME);
        } catch (Throwable t) {
            Constants.LOGGER.warn("[AutoModpack] Could not inject the early-service bridge launch plugin; in-place graphics-service mods may fail", t);
        }
    }

    private static final class PriorityPluginMap extends LinkedHashMap<String, Object> {
        private final String firstKey;

        PriorityPluginMap(String firstKey, Map<String, Object> initial) {
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
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        return EnumSet.noneOf(Phase.class);
    }

    @Override
    public void addResources(List<SecureJar> resources) {
        ensureRunsFirst();
    }

    @Override
    public void initializeLaunch(ITransformerLoader transformerLoader, NamedPath[] specialPaths) {
        Runnable bridge = onInitializeLaunch;
        if (bridge == null) return;
        try {
            bridge.run();
        } catch (Throwable t) {
            // Both bridge implementations guard themselves, but an escape here would propagate into
            // ModLauncher's plugin loop and abort the launch instead of degrading to a bridge miss.
            Constants.LOGGER.error("[AutoModpack] GAME-layer bridge failed; in-place early-service mods may not resolve", t);
        }
    }
}
