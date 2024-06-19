package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.neoforged.fml.loading.moddiscovery.ModDiscoverer;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_neoforge.mods.SetupMods;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public class EarlyModLocator extends AbstractJarFileModLocator {
    public static Logger LOGGER = LogManager.getLogger("AutoModpack/BootStrap");
    @Override
    public void initArguments(Map<String, ?> arguments) { }

    @Override
    public String name() {
        return "automodpack_bootstrap";
    }

    @Override
    public Stream<Path> scanCandidates() {
        // Took from connector's code https://github.com/Sinytra/Connector/blob/0514fec8f189b88c5cec54dc5632fbcee13d56dc/src/main/java/dev/su5ed/sinytra/connector/locator/ConnectorEarlyLocator.java#L28
        try {
            Method method = FMLLoader.class.getDeclaredMethod("getModDiscoverer");
            method.setAccessible(true);
            ModDiscoverer discoverer = (ModDiscoverer) method.invoke(null);
            Field field = ModDiscoverer.class.getDeclaredField("dependencyLocatorList");
            field.setAccessible(true);
            List<IDependencyLocator> dependencyLocatorList = (List<IDependencyLocator>) field.get(discoverer);
            // 1 - move under; 0 - preserve original order
            dependencyLocatorList.sort(Comparator.comparingInt(loc -> loc instanceof LazyModLocator ? 1 : 0));
        } catch (Throwable t) {
            LOGGER.error("Error sorting FML dependency locators", t);
        }

        new Preload();
        return SetupMods.modsToAdd.stream();
    }
}