package pl.skidam.automodpack_loader_core_forge;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IModuleLayerManager;
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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

//    public static List<IModLocator> wModLocatorList = new ArrayList<>();

    @Override
    public Stream<Path> scanCandidates() {

        new Preload();

        SetupMods.modsToAdd.forEach(path -> LOGGER.info("Adding mod: " + path.getFileName()));

        try {
            // Adds new locators from newly added mods
            // refresh locator lists
//            final var moduleLayerManager = Launcher.INSTANCE.environment().findModuleLayerManager().orElseThrow();
//            var newModLocators = ServiceLoader.load(moduleLayerManager.getLayer(IModuleLayerManager.Layer.SERVICE).orElseThrow(), IModLocator.class);
//            Map<Class<IModLocator>, IModLocator> newModLocatorMap = new HashMap<>();
//            newModLocators.forEach(modLocator -> newModLocatorMap.put((Class<IModLocator>) modLocator.getClass(), modLocator));
//
//
//            var newDependencyLocators = ServiceLoader.load(moduleLayerManager.getLayer(IModuleLayerManager.Layer.SERVICE).orElseThrow(), IDependencyLocator.class);
//            Map<Class<IDependencyLocator>, IDependencyLocator> newDependencyLocatorMap = new HashMap<>();
//            newDependencyLocators.forEach(dependencyLocator -> newDependencyLocatorMap.put((Class<IDependencyLocator>) dependencyLocator.getClass(), dependencyLocator));

            Method method = FMLLoader.class.getDeclaredMethod("getModDiscoverer");
            method.setAccessible(true);
            ModDiscoverer discoverer = (ModDiscoverer) method.invoke(null);

//
//            Field modLocList = ModDiscoverer.class.getDeclaredField("modLocatorList");
//            modLocList.setAccessible(true);
//
//            List<IModLocator> originalModLocatorList = (List<IModLocator>) modLocList.get(discoverer);
//            Map<Class<IModLocator>, IModLocator> originalModLocatorMap = new HashMap<>();
//            originalModLocatorList.forEach(locator -> originalModLocatorMap.put((Class<IModLocator>) locator.getClass(), locator));


            Field dependencyLocList = ModDiscoverer.class.getDeclaredField("dependencyLocatorList");
            dependencyLocList.setAccessible(true);

            List<IDependencyLocator> originalDependencyLocatorList = (List<IDependencyLocator>) dependencyLocList.get(discoverer);
//            Map<Class<IDependencyLocator>, IDependencyLocator> originalDependencyLocatorMap = new HashMap<>();
//            originalDependencyLocatorList.forEach(locator -> originalDependencyLocatorMap.put((Class<IDependencyLocator>) locator.getClass(), locator));

//            newModLocatorMap.forEach((clazz, modLocator) -> System.out.println("New mod locator: " + clazz.getName()));
//            originalModLocatorMap.forEach((clazz, modLocator) -> System.out.println("Original mod locator: " + clazz.getName()));

//            newDependencyLocatorMap.forEach((clazz, depLocator) -> System.out.println("New dependency locator: " + clazz.getName()));
//            originalDependencyLocatorMap.forEach((clazz, depLocator) -> System.out.println("Original dependency locator: " + clazz.getName()));
//

//            wModLocatorList.addAll(originalModLocatorList);
//            // make it proxy
//            modLocList.set(discoverer, Proxy.newProxyInstance(originalModLocatorList.getClass().getClassLoader(), originalModLocatorList.getClass().getInterfaces(), new ListProxy()));
//
//            // check if the lists are equal to the ones we got from the ServiceLoader
//            newModLocatorMap.forEach((clazz, modLocator) -> {
//                if (!originalModLocatorMap.containsKey(clazz)) {
//                    LOGGER.warn("Adding mod locator: " + clazz.getName());
//                    wModLocatorList.add(modLocator);
//                }
//            });
//
////            newDependencyLocatorMap.forEach((clazz, depLocator)  -> {
////                if (!originalModLocatorMap.containsKey(clazz)) {
////                    LOGGER.warn("Adding dependency locator: " + clazz.getName());
////                    originalDependencyLocatorList.add(depLocator);
////                }
////            });
//
//            // 1 - move under; 0 - preserve original order
//            originalDependencyLocatorList.forEach(loc -> System.out.println(loc.getClass().getName()));
            originalDependencyLocatorList.sort(Comparator.comparingInt(loc -> loc instanceof LazyModLocator ? 1 : 0));
        } catch (Throwable t) {
            LOGGER.error("Error sorting FML dependency locators", t);
        }


        return SetupMods.modsToAdd.stream();
    }
//
//    // Proxy is necessary to be able to add/remove mods there
//    // See: https://gist.github.com/Skidamek/605fe5bbdd9b62a5aeef823e5a5ba3d9
//    // And: https://github.com/FabricMC/fabric-loader/blob/c56386687036dbef28b065da4e3af63671240f38/src/main/java/net/fabricmc/loader/impl/FabricLoaderImpl.java#L465
//    public static class ListProxy implements InvocationHandler {
//        @Override
//        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
//            return method.invoke(wModLocatorList, args);
//        }
//    }

}