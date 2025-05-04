package pl.skidam.automodpack_loader_core_fabric_16.mods;

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
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_loader_core_fabric.FabricLanguageAdapter;

import java.nio.file.Path;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_loader_core_fabric.FabricLoaderImplAccessor.*;

@SuppressWarnings({"unchecked", "unused"})
public class ModpackLoader16 implements ModpackLoaderService {
    private final Map<String, Set<ModCandidateImpl>> envDisabledMods = new HashMap<>();
    
    @Override
    public void loadModpack(List<Path> modpackMods) {

        Path modpackModsDir = null;

        for (Path path : modpackMods) {
            modpackModsDir = path.toAbsolutePath().normalize().getParent();
            break;
        }

        if (modpackModsDir == null) {
            return;
        }

        try {
            LOGGER.info("Discovering mods from {}", modpackModsDir.getParent().getFileName() + "/" + modpackModsDir.getFileName());

            List<ModCandidateImpl> candidates;
            candidates = (List<ModCandidateImpl>) discoverMods(modpackModsDir);
            candidates = (List<ModCandidateImpl>) resolveMods(candidates);

            METHOD_DUMP_MOD_LIST.invoke(FabricLoaderImpl.INSTANCE, candidates);

            addMods(candidates);
            setupLanguageAdapters(candidates);
        } catch (Exception e) {
            FabricGuiEntry.displayCriticalError(e, true);
        }
    }

    @Override
    public List<FileInspection.Mod> getModpackNestedConflicts(Path modpackDir) {
        Path modpackModsDir = modpackDir.resolve("mods");
        Path standardModsDir = MODS_DIR;

        List<ModCandidateImpl> modpackNestedMods = new ArrayList<>();
        List<ModCandidateImpl> standardNestedMods = new ArrayList<>();

        try {
            List<ModCandidateImpl> candidates = (List<ModCandidateImpl>) discoverMods(modpackModsDir);
            candidates.forEach(it -> applyPaths(it, false));

            for (ModCandidateImpl candidate : candidates) {
                if (!candidate.isRoot()) {
                    continue;
                }

                List<ModCandidateImpl> nestedMods = getNestedMods(candidate);
                nestedMods = getOnlyNewestMods(nestedMods);

                boolean isStandard = !candidate.getPaths().get(0).toAbsolutePath().toString().contains(modpackModsDir.toAbsolutePath().toString());
                if (isStandard) {
                    standardNestedMods.addAll(nestedMods);
                } else {
                    modpackNestedMods.addAll(nestedMods);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Remove older versions of the same mods
        modpackNestedMods = getOnlyNewestMods(modpackNestedMods);
        standardNestedMods = getOnlyNewestMods(standardNestedMods);

        List<ModCandidateImpl> conflictingNestedModsImpl = new ArrayList<>();

        for (ModCandidateImpl standardNestedMod : standardNestedMods) {
            for (ModCandidateImpl modpackNestedMod : modpackNestedMods) {
                if (!standardNestedMod.getId().equals(modpackNestedMod.getId()))
                    continue;

                if (modpackNestedMod.getVersion().compareTo(standardNestedMod.getVersion()) > 0) {
                    conflictingNestedModsImpl.add(modpackNestedMod);
                }
            }
        }

        // Remove older versions of the same mods
        conflictingNestedModsImpl = getOnlyNewestMods(conflictingNestedModsImpl);

        List<ModCandidateImpl> modsNestedDeps = new ArrayList<>();

        // Add nested dependencies
        for (ModCandidateImpl modCandidate : conflictingNestedModsImpl) {
            List<ModCandidateImpl> nestedDeps = getNestedDeps(modCandidate);
            for (ModCandidateImpl nestedDep : nestedDeps) {
                if (conflictingNestedModsImpl.stream().anyMatch(it -> it.getId().equals(nestedDep.getId())))
                    continue;

                if (modsNestedDeps.stream().anyMatch(it -> it.getId().equals(nestedDep.getId())))
                    continue;

                modsNestedDeps.add(nestedDep);
            }
        }

        conflictingNestedModsImpl.addAll(modsNestedDeps);

        List<String> originModIds = new ArrayList<>();

        for (ModCandidateImpl mod : conflictingNestedModsImpl) {
            mod.getParentMods().stream().filter(ModCandidateImpl::isRoot).findFirst().map(ModCandidateImpl::getId).ifPresent(originModIds::add);
        }

        // These are nested mods which we need to force load from standard mods dir
        List<FileInspection.Mod> conflictingNestedMods = new ArrayList<>();

        for (ModCandidateImpl mod : conflictingNestedModsImpl) {
            // Check mods provides, if theres some mod which is named with the same id as some other mod 'provides' remove the mod which provides that id as well, otherwise loader will crash
            if (originModIds.stream().anyMatch(mod.getProvides()::contains))
                continue;

            Path path = mod.getPaths().get(0);
            if (path == null || path.toString().isEmpty())
                continue;

            String hash = CustomFileUtils.getHash(path);
            if (hash == null)
                continue;

            FileInspection.Mod conflictingMod = new FileInspection.Mod(
                    mod.getId(),
                    hash,
                    mod.getProvides(),
                    mod.getVersion().getFriendlyString(),
                    path,
                    LoaderManagerService.EnvironmentType.UNIVERSAL,
                    mod.getDependencies().stream().map(ModDependency::getModId).toList());

            conflictingNestedMods.add(conflictingMod);
        }

        return conflictingNestedMods;
    }

    private List<ModCandidateImpl> getNestedMods(ModCandidateImpl originMod) {
        List<ModCandidateImpl> mods = new ArrayList<>();
        for (ModCandidateImpl nested : originMod.getNestedMods()) {
            mods.add(nested);
            mods.addAll(getNestedMods(nested));
        }

        return mods;
    }

    // Needed for e.g. fabric api
    private List<ModCandidateImpl> getNestedDeps(ModCandidateImpl nestedMod) {
        List<ModCandidateImpl> deps = new ArrayList<>();

        ModCandidateImpl originMod;
        if (!nestedMod.isRoot()) {
            originMod = nestedMod.getParentMods().stream().toList().get(0);
        } else {
            originMod = nestedMod;
        }

        for (ModDependency dep : nestedMod.getDependencies()) {
            ModCandidateImpl candidate = originMod.getNestedMods().stream().filter(it -> it.getId().equals(dep.getModId())).findFirst().orElse(null);
            if (candidate == null) {
                continue;
            }

            deps.add(candidate);
        }

        return deps;
    }

    private List<ModCandidateImpl> getOnlyNewestMods(List<ModCandidateImpl> allMods) {
        List<ModCandidateImpl> latestMods = new ArrayList<>();

        for (ModCandidateImpl standardNestedMod : allMods) {
            // add mod to the standardLatestNestedMods if its id doesnt already exist or if it has a greater version then also delete the lower version
            boolean alreadyExists = latestMods.stream().anyMatch(existingMod -> {
                boolean hasSameId = existingMod.getId().equals(standardNestedMod.getId());
                boolean hasGreaterOrEqualVersion = existingMod.getVersion().compareTo(standardNestedMod.getVersion()) >= 0;

                return hasSameId && hasGreaterOrEqualVersion;
            });

            if (alreadyExists) {
                continue;
            }

            latestMods.removeIf(existingMod -> existingMod.getId().equals(standardNestedMod.getId()));

            latestMods.add(standardNestedMod);
        }

        return latestMods;
    }

    private Collection<ModCandidateImpl> discoverMods(Path modpackModsDir) throws ModResolutionException, IllegalAccessException {
        ModDiscoverer discoverer = new ModDiscoverer(new VersionOverrides(), new DependencyOverrides(FabricLoaderImpl.INSTANCE.getConfigDir()));

        List<?> candidateFinders = List.of(
                new ModContainerModCandidateFinder((List<ModContainer>) FabricLanguageAdapter.getAllMods().stream().toList()),
                new DirectoryModCandidateFinder(modpackModsDir, FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment()));

        FIELD_CANDIDATE_FINDERS.set(discoverer, candidateFinders);

        return discoverer.discoverMods(FabricLoaderImpl.INSTANCE, envDisabledMods);
    }

    private Collection<ModCandidateImpl> resolveMods(Collection<ModCandidateImpl> modCandidates) throws ModResolutionException {
        Set<String> modIds = new HashSet<>();
        for (var mod : FabricLanguageAdapter.getAllMods().stream().toList()) {
            ModContainerImpl container = (ModContainerImpl) mod;
            modIds.add(container.getMetadata().getId());
        }

        var candidates = ModResolver.resolve(modCandidates, FabricLoaderImpl.INSTANCE.getEnvironmentType(), envDisabledMods);
        candidates.removeIf(it -> modIds.contains(it.getId()));
        candidates.forEach(it -> applyPaths(it, true));

        return candidates;
    }

    private void addMods(Collection<ModCandidateImpl> candidates) {
        try {
            for (ModCandidateImpl candidate : candidates) {
                addMod(candidate);
            }
        } catch (Exception e) {
            FabricGuiEntry.displayCriticalError(e, true);
        }
    }

    public void addMod(ModCandidateImpl candidate) throws IllegalAccessException {
        ModContainerImpl container = new ModContainerImpl(candidate);
        FabricLanguageAdapter.addMod(container);

        var modMap = (Map<String, ModContainerImpl>) FIELD_MOD_MAP.get(FabricLoaderImpl.INSTANCE);

        modMap.put(candidate.getId(), container);

        for (String provides : candidate.getProvides()) {
            modMap.put(provides, container);
        }

        FIELD_MOD_MAP.set(FabricLoaderImpl.INSTANCE, modMap);

        if (!candidate.hasPath() && !candidate.isBuiltin()) {
            applyPaths(candidate, true);
        }

        for (Path it : candidate.getPaths()) {
            FabricLauncherBase.getLauncher().addToClassPath(it);
        }
    }

    private void applyPaths(ModCandidateImpl candidate, boolean remap) {
        try {
            Path cacheDir = FabricLoaderImpl.INSTANCE.getGameDir().resolve(FabricLoaderImpl.CACHE_DIR_NAME);
            Path processedModsDir = cacheDir.resolve("processedMods");

            if (remap && FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment() && System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE) != null) {
                RuntimeModRemapper.remap(Collections.singleton(candidate), cacheDir.resolve("tmp"), processedModsDir);
            }

            if (!candidate.hasPath() && !candidate.isBuiltin()) {
                candidate.setPaths(Collections.singletonList(candidate.copyToDir(processedModsDir, false)));
            }
        } catch (Exception e) {
            FabricGuiEntry.displayCriticalError(e, true);
        }
    }

    private void setupLanguageAdapters(Collection<ModCandidateImpl> candidates) throws IllegalAccessException {
        var adapterMap = (Map<String, LanguageAdapter>) FIELD_ADAPTER_MAP.get(FabricLoaderImpl.INSTANCE);
        for (ModCandidateImpl candidate : candidates) {

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
