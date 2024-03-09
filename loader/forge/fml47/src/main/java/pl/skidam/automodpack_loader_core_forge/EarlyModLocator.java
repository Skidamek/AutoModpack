package pl.skidam.automodpack_loader_core_forge;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.fml.loading.moddiscovery.ModDiscoverer;
import net.minecraftforge.forgespi.locating.IDependencyLocator;
import net.minecraftforge.forgespi.locating.IModLocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_forge.mods.SetupMods;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

        new Preload();

        SetupMods.modsToAdd.forEach(path -> LOGGER.info("Adding mod: " + path.getFileName()));

        // add these mods locators to the forge's list

        // Took from connector's code https://github.com/Sinytra/Connector/blob/0514fec8f189b88c5cec54dc5632fbcee13d56dc/src/main/java/dev/su5ed/sinytra/connector/locator/ConnectorEarlyLocator.java#L28
        try {
            Method method = FMLLoader.class.getDeclaredMethod("getModDiscoverer");
            method.setAccessible(true);
            ModDiscoverer discoverer = (ModDiscoverer) method.invoke(null);
            Field modLocList = ModDiscoverer.class.getDeclaredField("modLocatorList");
            modLocList.setAccessible(true);
            Field dependencyLocList = ModDiscoverer.class.getDeclaredField("dependencyLocatorList");
            dependencyLocList.setAccessible(true);
            List<IDependencyLocator> dependencyLocatorList = (List<IDependencyLocator>) dependencyLocList.get(discoverer);
            // 1 - move under; 0 - preserve original order
            dependencyLocatorList.sort(Comparator.comparingInt(loc -> loc instanceof LazyModLocator ? 1 : 0));

//            List<IModLocator> modLocatorList = (List<IModLocator>) modLocList.get(discoverer);
//            // search for the class which implements IModLocator
//            SetupMods.modsToAdd.forEach(path -> {
//                try {
//                    // open the jar file as zip
//                    ZipFile zipFile = new ZipFile(path.toFile());
//                    // open META-INF/services/net.minecraftforge.fml.loading.moddiscovery.IModLocator file
//                    // and META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator file
//                    Enumeration<? extends ZipEntry> entries = zipFile.entries();
//                    while (entries.hasMoreElements()) {
//                        ZipEntry entry = entries.nextElement();
//                        if (entry.getName().equals("META-INF/services/net.minecraftforge.fml.loading.moddiscovery.IModLocator")) {
//                            BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));
//                            String line;
//                            while ((line = reader.readLine()) != null) {
//                                Class<?> clazz = Class.forName(line);
//                                if (IModLocator.class.isAssignableFrom(clazz)) {
//                                    IModLocator modLocator = (IModLocator) clazz.getConstructor().newInstance();
//                                    modLocator.initArguments(Map.of("modPath", path));
//                                    modLocatorList.add(modLocator);
//                                }
//                            }
//                            reader.close();
//                        } else if (entry.getName().equals("META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator")) {
//                            BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));
//                            String line;
//                            while ((line = reader.readLine()) != null) {
//                                Class<?> clazz = Class.forName(line);
//                                if (IDependencyLocator.class.isAssignableFrom(clazz)) {
//                                    IDependencyLocator dependencyLocator = (IDependencyLocator) clazz.getConstructor().newInstance();
//                                    dependencyLocator.initArguments(Map.of("modPath", path));
//                                    dependencyLocatorList.add(dependencyLocator);
//                                }
//                            }
//                            reader.close();
//                        }
//                    }
//                    zipFile.close();
//                } catch (Throwable t) {
//                    LOGGER.error("Error adding mod locator", t);
//                }
//            });
        } catch (Throwable t) {
            LOGGER.error("Error sorting FML dependency locators", t);
        }


        return SetupMods.modsToAdd.stream();
    }
}