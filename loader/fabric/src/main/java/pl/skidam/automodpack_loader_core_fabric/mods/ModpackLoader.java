package pl.skidam.automodpack_loader_core_fabric.mods;

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
import pl.skidam.automodpack_loader_core.mods.ModpackLoaderService;
import pl.skidam.automodpack_loader_core_fabric.FabricLanguageAdapter;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_loader_core_fabric.FabricLoaderImplAccessor.*;

@SuppressWarnings({"unchecked", "unused"})
public class ModpackLoader implements ModpackLoaderService {
    private final Map<String, Set<ModCandidate>> envDisabledMods = new HashMap<>();

    @Override
    public void loadModpack(List<Path> modpackMods) {

        Path modpackDir = modpackMods.get(0).getParent();

        try {
            List<ModCandidate> candidates;
            candidates = (List<ModCandidate>) discoverMods(modpackDir);
            candidates = (List<ModCandidate>) resolveMods(candidates);

            METHOD_DUMP_MOD_LIST.invoke(FabricLoaderImpl.INSTANCE, candidates);

            addMods(candidates);
            setupLanguageAdapters(candidates);
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

    public Optional<ModContainer> getModContainer(Path path) {
        if (path == null) {
            return Optional.empty();
        }

        path = path.normalize();

        for (ModContainer modContainer : FabricLanguageAdapter.getAllMods()) {
            for (Path rootPath : modContainer.getRootPaths()) {
                FileSystem fileSys = rootPath.getFileSystem();
                Path modPath = Path.of(fileSys.toString()).toAbsolutePath();
                if (modPath.equals(path.toAbsolutePath())) {
                    System.out.println("Found mod: " + modPath);
                    return Optional.of(modContainer);
                } else {
                    System.out.println("Not found mod: " + modPath + " " + path.toAbsolutePath());
                }
            }
        }

        return Optional.empty();
    }

    public Optional<ModContainer> getModContainer(String modId) {
        if (modId == null) {
            return Optional.empty();
        }

        for (ModContainer modContainer : FabricLanguageAdapter.getAllMods()) {
            if (modId.equals(modContainer.getMetadata().getId())) {
                return Optional.of(modContainer);
            }
        }

        return Optional.empty();
    }


    private Collection<ModCandidate> discoverMods(Path modpack) throws ModResolutionException, IllegalAccessException, IOException {
        ModDiscoverer discoverer = new ModDiscoverer(new VersionOverrides(), new DependencyOverrides(FabricLoaderImpl.INSTANCE.getConfigDir()));

        List<Path> pathList = Files.list(modpack).toList();
        for (Path path : pathList) {
            if (!Files.isDirectory(path)) {
                continue;
            }

            if (!path.getFileName().toString().equals("mods")) {
                continue;
            }

            LOGGER.info("Discovering mods from {}", path.getParent().getFileName() + "/" + path.getFileName());

            List<?> candidateFinders = List.of(
                    new ModContainerModCandidateFinder(FabricLanguageAdapter.getAllMods().stream().toList()),
                    new DirectoryModCandidateFinder(path, FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment()));

            FIELD_CANDIDATE_FINDERS.set(discoverer, candidateFinders);
        }

        return discoverer.discoverMods(FabricLoaderImpl.INSTANCE, envDisabledMods);
    }

    private Collection<ModCandidate> resolveMods(Collection<ModCandidate> modCandidates) throws ModResolutionException {
        Set<String> modIds = new HashSet<>();
        for (var mod : FabricLanguageAdapter.getAllMods().stream().toList()) {
            modIds.add(mod.getMetadata().getId());
        }

        var candidates = ModResolver.resolve(modCandidates, FabricLoaderImpl.INSTANCE.getEnvironmentType(), envDisabledMods);
        candidates.removeIf(it -> modIds.contains(it.getId()));

        candidates.forEach(this::applyPaths);

        return candidates;
    }

    private void addMods(Collection<ModCandidate> candidates) {
        try {
            for (ModCandidate candidate : candidates) {
                addMod(candidate);
            }
        } catch (Exception e) {
            FabricGuiEntry.displayCriticalError(e, true);
        }
    }

    public void addMod(ModCandidate candidate) throws IllegalAccessException {
        ModContainerImpl container = new ModContainerImpl(candidate);
        FabricLanguageAdapter.mods.add(container);

        var modMap = (Map<String, ModContainerImpl>) FIELD_MOD_MAP.get(FabricLoaderImpl.INSTANCE);

        modMap.put(candidate.getId(), container);

        for (String provides : candidate.getProvides()) {
            modMap.put(provides, container);
        }

        FIELD_MOD_MAP.set(FabricLoaderImpl.INSTANCE, modMap);

        if (!candidate.hasPath() && !candidate.isBuiltin()) {
            applyPaths(candidate);
        }

        for (Path it : candidate.getPaths()) {
            FabricLauncherBase.getLauncher().addToClassPath(it);
        }
    }

    private void applyPaths(ModCandidate candidate) {
        try {
            Path cacheDir = FabricLoaderImpl.INSTANCE.getGameDir().resolve(FabricLoaderImpl.CACHE_DIR_NAME);
            Path processedModsDir = cacheDir.resolve("processedMods");

            if (FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment() && System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE) != null) {
                RuntimeModRemapper.remap(Collections.singleton(candidate), cacheDir.resolve("tmp"), processedModsDir);
            }

            if (!candidate.hasPath() && !candidate.isBuiltin()) {
                candidate.setPaths(Collections.singletonList(candidate.copyToDir(processedModsDir, false)));
            }
        } catch (Exception e) {
            FabricGuiEntry.displayCriticalError(e, true);
        }
    }

    private void setupLanguageAdapters(Collection<ModCandidate> candidates) throws IllegalAccessException {
        var adapterMap = (Map<String, LanguageAdapter>) FIELD_ADAPTER_MAP.get(FabricLoaderImpl.INSTANCE);
        for (ModCandidate candidate : candidates) {

            var definitions = candidate.getMetadata().getLanguageAdapterDefinitions();
            if (definitions.isEmpty()) {
                continue;
            }

            LOGGER.info("Setting up language adapter for {}", candidate.getId());

            for (var entry : definitions.entrySet()) {

                if (!candidate.getId().equals(MOD_ID) && adapterMap.containsKey(entry.getKey())) {

                    // TODO require restart or erase that package from vm and remove adapter from the map

                    FabricGuiEntry.displayCriticalError(new IllegalArgumentException("Duplicate language adapter ID: " + entry.getKey()), true);
                }

                try {
                    Class<?> adapterClass = Class.forName(entry.getValue(), true, FabricLauncherBase.getLauncher().getTargetClassLoader());
                    LanguageAdapter adapter = (LanguageAdapter) adapterClass.getConstructor().newInstance();
                    adapterMap.put(entry.getKey(), adapter);
                } catch (Exception e) {
                    FabricGuiEntry.displayCriticalError(new RuntimeException("Error setting up language adapter for " + entry.getKey(), e), true);
                }
            }
        }
    }
}
