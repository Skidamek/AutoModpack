package pl.skidam.automodpack_core_fabric.mods;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.*;
import net.fabricmc.loader.impl.gui.FabricGuiEntry;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.fabricmc.loader.impl.util.SystemProperties;
import pl.skidam.automodpack_core.mods.SetupModsService;
import settingdust.preloadingtricks.fabric.FabricLanguageProviderCallback;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class SetupMods implements SetupModsService {
    private final FabricLanguageProviderCallback.FabricModSetupService service = FabricLanguageProviderCallback.FabricModSetupService.INSTANCE;
    private final Map<String, Set<ModCandidate>> envDisabledMods = new HashMap<>();
    private Field adapterMapField;
    private Method addModMethod;
    private Method addCandidateFinderMethod;
    private Method dumpModListMethod;

    {
        try {
            adapterMapField = FabricLoaderImpl.class.getDeclaredField("adapterMap");
            adapterMapField.setAccessible(true);

            addModMethod = FabricLoaderImpl.class.getDeclaredMethod("addMod", ModCandidate.class);
            addModMethod.setAccessible(true);

            Method[] methods = ModDiscoverer.class.getDeclaredMethods();
            for (Method method : methods) {
                if (!method.getName().equals("addCandidateFinder")) {
                    continue;
                }

                addCandidateFinderMethod = method;
                addCandidateFinderMethod.setAccessible(true);
                break;
            }

            if (addCandidateFinderMethod == null) {
                throw new NoSuchMethodException("addCandidateFinder");
            }

            dumpModListMethod = FabricLoaderImpl.class.getDeclaredMethod("dumpModList", List.class);
            dumpModListMethod.setAccessible(true);
        } catch (Exception e) {
            FabricGuiEntry.displayCriticalError(e, true);
        }
    }


    @Override
    public void loadModpack(Path modpack) {
        if (modpack == null || !Files.isDirectory(modpack)) {
            LOGGER.warn("Incorrect path to modpack");
            return;
        }

        modpack = modpack.toAbsolutePath();

        try {
            List<ModCandidate> candidates;
            candidates = (List<ModCandidate>) discoverMods(modpack);
            candidates = (List<ModCandidate>) resolveMods(candidates);

            dumpModListMethod.invoke(FabricLoaderImpl.INSTANCE, candidates);

            addMods(candidates);
            setupLanguageAdapter(candidates);

        } catch (Exception e) {
            FabricGuiEntry.displayCriticalError(e, true);
        }
    }

    @Override
    public void removeMod(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            ModContainer originalContainer = getModContainer(path);
            if (originalContainer == null) {
                LOGGER.error("Failed to get container of mod {} to remove from loader", path);
                return;
            }

            Collection<ModContainer> containers = getNestedContainers(originalContainer);
            containers.add(originalContainer);

            for (ModContainer container : containers) {

                FabricLanguageProviderCallback.FabricModSetupService.INSTANCE.remove((ModContainerImpl) container);

                Field modsField = FabricLoaderImpl.class.getDeclaredField("mods");
                modsField.setAccessible(true);
                var mods = (List<ModContainerImpl>) modsField.get(FabricLoaderImpl.INSTANCE);
                mods.remove((ModContainerImpl) container);
                modsField.set(FabricLoaderImpl.INSTANCE, mods);

                Field modMapField = FabricLoaderImpl.class.getDeclaredField("modMap");
                modMapField.setAccessible(true);
                var modMap = (Map<String, ModContainerImpl>) modMapField.get(FabricLoaderImpl.INSTANCE);
                modMap.remove(container.getMetadata().getId());
                modMapField.set(FabricLoaderImpl.INSTANCE, modMap);

                var adapterMap = (Map<String, LanguageAdapter>) adapterMapField.get(FabricLoaderImpl.INSTANCE);
                adapterMap.remove(container.getMetadata().getId());
                adapterMapField.set(FabricLoaderImpl.INSTANCE, adapterMap);
            }

            FabricLauncherBase.getLauncher().getClassPath().remove(path);

        } catch (Exception e) {
            FabricGuiEntry.displayCriticalError(e, true);
        }
    }

    public Collection<ModContainer> getNestedContainers(ModContainer originalContainer) {
        Collection<ModContainer> containers = new ArrayList<>(originalContainer.getContainedMods());
        Collection<ModContainer> tempContainers = new ArrayList<>();

        for (ModContainer container : containers) {
            tempContainers.addAll(getNestedContainers(container));
        }

        containers.addAll(tempContainers);

        return containers;
    }

    public ModContainer getModContainer(Path path) {
        if (path == null) {
            return null;
        }
        for (ModContainer modContainer : FabricLoader.getInstance().getAllMods()) {
            FileSystem fileSys = modContainer.getRootPaths().get(0).getFileSystem();
            Path modPath = Paths.get(fileSys.toString());
            if (modPath.toAbsolutePath().equals(path.toAbsolutePath())) {
                return modContainer;
            }
        }

        return null;
    }


    @Override
    public void addMod(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            ModDiscoverer discoverer = new ModDiscoverer(new VersionOverrides(), new DependencyOverrides(FabricLoaderImpl.INSTANCE.getConfigDir()));
            List<ModContainerImpl> modContainers = (List<ModContainerImpl>) service.all();

            addCandidateFinderMethod.invoke(
                discoverer,
                new ModContainerModCandidateFinder(modContainers)
            );

            addCandidateFinderMethod.invoke(
                discoverer,
                new PathModCandidateFinder(
                    path,
                    FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment()
                )
            );

            List<ModCandidate> candidates = discoverer.discoverMods(FabricLoaderImpl.INSTANCE, envDisabledMods);
            candidates = (List<ModCandidate>) resolveMods(candidates);

            dumpModListMethod.invoke(FabricLoaderImpl.INSTANCE, candidates);

            addMods(candidates);
            setupLanguageAdapter(candidates);
        } catch (Exception e) {
            FabricGuiEntry.displayCriticalError(e, true);
        }
    }

    private Collection<ModCandidate> discoverMods(Path modpack) throws ModResolutionException, InvocationTargetException, IllegalAccessException, IOException {
        ModDiscoverer discoverer = new ModDiscoverer(new VersionOverrides(), new DependencyOverrides(FabricLoaderImpl.INSTANCE.getConfigDir()));
        addCandidateFinderMethod.invoke(
            discoverer,
            new ModContainerModCandidateFinder((List<ModContainerImpl>) service.all())
        );

        List<Path> pathList = Files.walk(modpack, 1).toList();
        for (Path path : pathList) {
            if (!Files.isDirectory(path)) {
                continue;
            }

            if (!path.getFileName().toString().equals("mods")) {
                continue;
            }

            LOGGER.info("Discovering mods from {}", path.getParent().getFileName() + "/" + path.getFileName());
            addCandidateFinderMethod.invoke(
                discoverer,
                new DirectoryModCandidateFinder(
                    path,
                    FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment()
                )
            );
        }

        return discoverer.discoverMods(FabricLoaderImpl.INSTANCE, envDisabledMods);
    }

    private Collection<ModCandidate> resolveMods(Collection<ModCandidate> modCandidates) throws ModResolutionException, IOException {
        Set<String> modId = new HashSet<>();
        for (ModContainerImpl it : service.all()) {
            modId.add(it.getMetadata().getId());
        }
        Path cacheDir = FabricLoaderImpl.INSTANCE.getGameDir().resolve(FabricLoaderImpl.CACHE_DIR_NAME);
        Path processedModsDir = cacheDir.resolve("processedMods");

        var candidates = ModResolver.resolve(modCandidates, FabricLoaderImpl.INSTANCE.getEnvironmentType(), envDisabledMods);
        candidates.removeIf(it -> modId.contains(it.getId()));

        if (FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment() && System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE) != null) {
            RuntimeModRemapper.remap(candidates, cacheDir.resolve("tmp"), processedModsDir);
        }

        for (ModCandidate mod : candidates) {
            if (!mod.hasPath() && !mod.isBuiltin()) {
                mod.setPaths(Collections.singletonList(mod.copyToDir(processedModsDir, false)));
            }
        }

        return candidates;
    }

    private void addMods(Collection<ModCandidate> candidates) {
        for (ModCandidate candidate : candidates) {
            try {
                addModMethod.invoke(FabricLoaderImpl.INSTANCE, candidate);
            } catch (Exception e) {
                throw new RuntimeException("Error invoking addMod method", e);
            }
            for (Path it : candidate.getPaths()) {
                FabricLauncherBase.getLauncher().addToClassPath(it);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void setupLanguageAdapter(Collection<ModCandidate> candidates) throws IllegalAccessException {
        var adapterMap = (Map<String, LanguageAdapter>) adapterMapField.get(FabricLoaderImpl.INSTANCE);
        for (ModCandidate candidate : candidates) {

            var definitions = candidate.getMetadata().getLanguageAdapterDefinitions();
            if (definitions.isEmpty()) {
                continue;
            }

            LOGGER.info("Setting up language adapter for {}", candidate.getId());

            for (var entry : definitions.entrySet()) {

//                if (adapterMap.containsKey(entry.getKey())) {
//                    throw new IllegalArgumentException("Duplicate language adapter ID: " + entry.getKey());
//                }

                try {
                    Class<?> adapterClass = Class.forName(entry.getValue(), true, FabricLauncherBase.getLauncher().getTargetClassLoader());
                    LanguageAdapter adapter = (LanguageAdapter) adapterClass.getConstructor().newInstance();
                    adapterMap.put(entry.getKey(), adapter);
                } catch (Exception e) {
                    throw new RuntimeException("Error setting up language adapter for " + entry.getKey(), e);
                }
            }
        }
    }
}
