package pl.skidam.automodpack_loader_core_fabric_15.mods;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.*;
import net.fabricmc.loader.impl.gui.FabricGuiEntry;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.fabricmc.loader.impl.util.SystemProperties;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_loader_core_fabric.FabricLanguageAdapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_loader_core_fabric.FabricLoaderImplAccessor.*;

@SuppressWarnings({"unchecked", "unused"})
public class ModpackLoader15 implements ModpackLoaderService {
    private final Map<String, Set<ModCandidate>> envDisabledMods = new HashMap<>();

    public static List<ModCandidate> nestedMods = new ArrayList<>();

    @Override
    public void loadModpack(List<Path> modpackMods) {

        Path modpackDir = null;

        for (Path path : modpackMods) {
            modpackDir = path.toAbsolutePath().normalize().getParent();
            break;
        }

        if (modpackDir == null) {
            return;
        }

        try {
            List<ModCandidate> candidates;
            candidates = (List<ModCandidate>) discoverMods(modpackDir);
            candidates = (List<ModCandidate>) resolveMods(candidates);

            candidates.forEach(it -> it.getNestedMods().forEach(nested -> {
                List<ModCandidate> thizNestedMods = getNestedMods(nested, new ArrayList<>());
                nestedMods.addAll(thizNestedMods);
            }));

            METHOD_DUMP_MOD_LIST.invoke(FabricLoaderImpl.INSTANCE, candidates);

            addMods(candidates);
            setupLanguageAdapters(candidates);
        } catch (Exception e) {
            FabricGuiEntry.displayCriticalError(e, true);
        }
    }

    @Override
    public boolean prepareModpack(Path modpackDir, Set<String> ignoredMods) throws IOException {
        return false;
    }

    private List<ModCandidate> getNestedMods(ModCandidate modCandidate, List<ModCandidate> nestedMods) {
        for (var nested : modCandidate.getNestedMods()) {
            if (nested == null) {
                continue;
            }

            if (nested.getOriginPaths().size() > 1) {
                LOGGER.warn("Mod {} has more than one origin path: {}", nested.getId(), nested.getOriginPaths());
            }

            nestedMods.add(nested);
            getNestedMods(nested, nestedMods);
        }

        return nestedMods;
    }

    private Collection<ModCandidate> discoverMods(Path modpackModsDir) throws ModResolutionException, IllegalAccessException, IOException {
        ModDiscoverer discoverer = new ModDiscoverer(new VersionOverrides(), new DependencyOverrides(FabricLoaderImpl.INSTANCE.getConfigDir()));

        LOGGER.info("Discovering mods from {}", modpackModsDir.getParent().getFileName() + "/" + modpackModsDir.getFileName());

        List<?> candidateFinders = List.of(
                new ModContainerModCandidateFinder((List<ModContainer>) FabricLanguageAdapter.getAllMods().stream().toList()),
                new DirectoryModCandidateFinder(modpackModsDir, FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment()));

        FIELD_CANDIDATE_FINDERS.set(discoverer, candidateFinders);


        return discoverer.discoverMods(FabricLoaderImpl.INSTANCE, envDisabledMods);
    }

    private Collection<ModCandidate> resolveMods(Collection<ModCandidate> modCandidates) throws ModResolutionException {
        Set<String> modIds = new HashSet<>();
        for (var mod : FabricLanguageAdapter.getAllMods().stream().toList()) {
            ModContainerImpl container = (ModContainerImpl) mod;
            modIds.add(container.getMetadata().getId());
        }

        var candidates = ModResolver.resolve(modCandidates, FabricLoaderImpl.INSTANCE.getEnvironmentType(), envDisabledMods);
        candidates.removeIf(it -> modIds.contains(it.getId()));

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
        FabricLanguageAdapter.addMod(container);

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
