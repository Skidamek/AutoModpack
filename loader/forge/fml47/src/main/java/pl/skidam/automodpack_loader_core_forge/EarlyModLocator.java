package pl.skidam.automodpack_loader_core_forge;

import com.google.common.collect.Maps;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import net.minecraftforge.fml.loading.*;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.fml.loading.moddiscovery.InvalidModFileException;
import net.minecraftforge.fml.loading.moddiscovery.ModDiscoverer;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public class EarlyModLocator extends AbstractJarFileModLocator {
    private final Logger LOGGER = LogManager.getLogger("AutoModpack/BootStrap");
    private Map<String, ?> arguments;

    @Override
    public void initArguments(Map<String, ?> arguments) {
        this.arguments = arguments;
    }
    @Override
    public String name() {
        return "automodpack_bootstrap";
    }


    @Override
    public Stream<Path> scanCandidates() {

        new Preload();

        // we would need to force load there e.g. connector mod and its locators to loader
        // and in the lazy mod locator load its dependency locator

//        try {
//            boolean success = loadNewModLocators();
//            if (success) {
//                LOGGER.info("Successfully loaded new mod locators");
//            }
//        } catch (Exception e) {
//            LOGGER.error("Error loading new mod locators", e);
//        }

        try {
            Method method = FMLLoader.class.getDeclaredMethod("getModDiscoverer");
            method.setAccessible(true);
            ModDiscoverer discoverer = (ModDiscoverer) method.invoke(null);

            Field dependencyLocList = ModDiscoverer.class.getDeclaredField("dependencyLocatorList");
            dependencyLocList.setAccessible(true);

            List<IDependencyLocator> originalDependencyLocatorList = (List<IDependencyLocator>) dependencyLocList.get(discoverer);

            // 1 - move under; 0 - preserve original order
            originalDependencyLocatorList.sort(Comparator.comparingInt(loc -> loc instanceof LazyModLocator ? 1 : 0));
        } catch (Exception e) {
            LOGGER.error("Error sorting FML dependency locators", e);
        }


        return ModpackLoader.modsToLoad.stream();
    }

    private boolean loadNewModLocators() throws MalformedURLException {
        // Adds new locators from newly added mods
        // refresh locator lists
        final var moduleLayerManager = Launcher.INSTANCE.environment().findModuleLayerManager().orElseThrow();

        var modLocators = ServiceLoader.load(moduleLayerManager.getLayer(IModuleLayerManager.Layer.SERVICE).orElseThrow(), IModLocator.class);
        var dependencyLocators = ServiceLoader.load(moduleLayerManager.getLayer(IModuleLayerManager.Layer.SERVICE).orElseThrow(), IDependencyLocator.class);

        HashMap<String, IModLocator> newModLocators = new HashMap<>();
        HashMap<String, IDependencyLocator> newDependencyLocators = new HashMap<>();

        for (Path jarPath : ModpackLoader.modsToLoad) {
            URL newJarUrl = jarPath.toUri().toURL();
            URLClassLoader newClassLoader = new URLClassLoader(new URL[]{newJarUrl}, LazyModLocator.class.getClassLoader());

            var IModLocatorProviders = ServiceLoader.load(IModLocator.class, newClassLoader);
            var IDependencyLocatorProviders = ServiceLoader.load(IDependencyLocator.class, newClassLoader);

            for (IModLocator modLocator : IModLocatorProviders) {
                newModLocators.put(modLocator.getClass().getName(), modLocator);
            }

            for (IDependencyLocator dependencyLocator : IDependencyLocatorProviders) {
                newDependencyLocators.put(dependencyLocator.getClass().getName(), dependencyLocator);
            }
        }

        for (IModLocator modLocator : modLocators) {
            // check if modLocator exists in newModLocators, if so remove from newModLocators
            newModLocators.remove(modLocator.getClass().getName());
        }


        for (IDependencyLocator dependencyLocator : dependencyLocators) {
            // check if modLocator exists in newModLocators, if so remove from newModLocators
            newDependencyLocators.remove(dependencyLocator.getClass().getName());
        }

        if (newModLocators.isEmpty() && newDependencyLocators.isEmpty()) {
            return false;
        }

        LOGGER.error("Done loading new locators");

        LOGGER.warn("New mod locators: ");
        newModLocators.forEach((name, clazz) -> LOGGER.info(name));

        LOGGER.warn("New dependency locators: ");
        newDependencyLocators.forEach((name, clazz) -> LOGGER.info(name));

        // run new mod locators
        newModLocators.forEach((name, modLocator) -> modLocator.initArguments(arguments));
        newDependencyLocators.forEach((name, dependencyLocator) -> dependencyLocator.initArguments(arguments));

        return discoverMods(newModLocators.values());
    }

    public List<IModLocator.ModFileOrException> candidates = new ArrayList<>();

    // Based of forges code
    private boolean discoverMods(Collection<IModLocator> modLocators) {
        LOGGER.info("Scanning for mods and other resources to load. We know {} ways to find mods", modLocators.size());
        List<ModFile> loadedFiles = new ArrayList<>();
        List<EarlyLoadingException.ExceptionData> discoveryErrorData = new ArrayList<>();
        boolean successfullyLoadedMods = true;
        List<IModFileInfo> brokenFiles = new ArrayList<>();
        ImmediateWindowHandler.updateProgress("Discovering mod files");
        //Loop all mod locators to get the prime mods to load from.
        for (IModLocator locator : modLocators) {
            try {
                LOGGER.info("Trying locator {}", locator);

                // FIXME: this crashes
                // run new thread from locator class
                Thread contextThread = new Thread(() -> {
                    try {
                        candidates = locator.scanMods();
                    } catch (Exception e) {
                        LOGGER.error("Error running locator {}", locator, e);
                    }
                });
                contextThread.setContextClassLoader(locator.getClass().getClassLoader());

                contextThread.start();
                contextThread.join();


                LOGGER.info("Locator {} found {} candidates or errors", locator, candidates.size());
                var exceptions = candidates.stream().map(IModLocator.ModFileOrException::ex).filter(Objects::nonNull).toList();
                if (!exceptions.isEmpty()) {
                    LOGGER.info("Locator {} found {} invalid mod files", locator, exceptions.size());
                    brokenFiles.addAll(exceptions.stream().map(e->e instanceof InvalidModFileException ime ? ime.getBrokenFile() : null).filter(Objects::nonNull).toList());
                }
                var locatedFiles = candidates.stream().map(IModLocator.ModFileOrException::file).filter(Objects::nonNull).collect(Collectors.toList());

                var badModFiles = locatedFiles.stream().filter(file -> !(file instanceof ModFile)).toList();
                if (!badModFiles.isEmpty()) {
                    LOGGER.info("Locator {} returned {} files which is are not ModFile instances! They will be skipped!", locator, badModFiles.size());
                    brokenFiles.addAll(badModFiles.stream().map(IModFile::getModFileInfo).toList());
                }
                locatedFiles.removeAll(badModFiles);
                LOGGER.info("Locator {} found {} valid mod files", locator, locatedFiles.size());
                handleLocatedFiles(loadedFiles, locatedFiles);
            } catch (InvalidModFileException imfe) {
                // We don't generally expect this exception, since it should come from the candidates stream above and be handled in the Locator, but just in case.
                LOGGER.error("Locator {} found an invalid mod file {}", locator, imfe.getBrokenFile(), imfe);
                brokenFiles.add(imfe.getBrokenFile());
            } catch (EarlyLoadingException exception) {
                LOGGER.error( "Failed to load mods with locator {}", locator, exception);
                discoveryErrorData.addAll(exception.getAllData());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        //First processing run of the mod list. Any duplicates will cause resolution failure and dependency loading will be skipped.
        Map<IModFile.Type, List<ModFile>> modFilesMap = Maps.newHashMap();
        try {
            final UniqueModListBuilder modsUniqueListBuilder = new UniqueModListBuilder(loadedFiles);
            final UniqueModListBuilder.UniqueModListData uniqueModsData = modsUniqueListBuilder.buildUniqueList();

            //Grab the temporary results.
            //This allows loading to continue to a base state, in case dependency loading fails.
            modFilesMap = uniqueModsData.modFiles().stream()
                    .collect(Collectors.groupingBy(IModFile::getType));
            loadedFiles = uniqueModsData.modFiles();
        }
        catch (EarlyLoadingException exception) {
            LOGGER.error("Failed to build unique mod list after mod discovery.", exception);
            discoveryErrorData.addAll(exception.getAllData());
            successfullyLoadedMods = false;
        }

        return successfullyLoadedMods;
    }

    // Copied from forge
    private void handleLocatedFiles(final List<ModFile> loadedFiles, final List<IModFile> locatedFiles) {
        var locatedModFiles = locatedFiles.stream().filter(ModFile.class::isInstance).map(ModFile.class::cast).toList();
        for (IModFile mf : locatedModFiles) {
            LOGGER.info("Found mod file {} of type {} with provider {}", mf.getFileName(), mf.getType(), mf.getProvider());
        }
        loadedFiles.addAll(locatedModFiles);
    }
}