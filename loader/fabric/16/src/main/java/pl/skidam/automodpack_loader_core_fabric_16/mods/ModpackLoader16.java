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
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_loader_core.utils.VersionParser;
import pl.skidam.automodpack_loader_core_fabric.FabricLanguageAdapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_loader_core_fabric.FabricLoaderImplAccessor.*;

// suppress warnings for method invocation will produce 'NullPointerException'
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
    public boolean prepareModpack(Path modpackDir, Set<String> ignoredMods) throws IOException {
        Path modpackModsDir = modpackDir.resolve("mods");
        Path standardModsDir = MODS_DIR;

        List<LoaderManagerService.Mod> modpackNestedMods = new ArrayList<>();
        List<LoaderManagerService.Mod> standardNestedMods = new ArrayList<>();
        List<LoaderManagerService.Mod> originMods = new ArrayList<>();

        try {
            List<ModCandidateImpl> candidates = (List<ModCandidateImpl>) discoverMods(modpackModsDir);
            candidates.forEach(it -> applyPaths(it, false));

            for (ModCandidateImpl candidate : candidates) {
                originMods.add(new LoaderManagerService.Mod(candidate.getId(), new HashSet<>(candidate.getMetadata().getProvides()), candidate.getMetadata().getVersion().getFriendlyString(), candidate.getPaths().get(0), LoaderManagerService.EnvironmentType.UNIVERSAL, candidate.getMetadata().getDependencies().stream().filter(d -> d.getKind().equals(ModDependency.Kind.DEPENDS)).map(ModDependency::getModId).toList()));
                List<LoaderManagerService.Mod> thizNestedMods = getNestedMods(candidate);
                LOGGER.info("Nested mods for {}: {}", candidate.getId(), thizNestedMods.stream().map(LoaderManagerService.Mod::modID).toList());
                boolean isStandard = !candidate.getPaths().get(0).toAbsolutePath().toString().contains(modpackModsDir.toAbsolutePath().toString());
                if (isStandard) {
                    standardNestedMods.addAll(thizNestedMods);
                } else {
                    modpackNestedMods.addAll(thizNestedMods);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return true;
        }

        LOGGER.warn("Standard nested mods: {}", standardNestedMods.stream().map(LoaderManagerService.Mod::modID).toList());
        LOGGER.warn("Modpack nested mods: {}", modpackNestedMods.stream().map(LoaderManagerService.Mod::modID).toList());


        // mod from standard mods dir - mod from modpack mods dir
        Map<LoaderManagerService.Mod, LoaderManagerService.Mod> conflictingNestedMods = new HashMap<>();

        for (LoaderManagerService.Mod standardNestedMod : standardNestedMods) {
            for (LoaderManagerService.Mod modpackNestedMod : modpackNestedMods) {
                if (standardNestedMod.modID().equals(modpackNestedMod.modID())) {

                    LOGGER.info("Found potentially conflicting nested mods: {} - {}", standardNestedMod.modID(), modpackNestedMod.modID());

                    Integer[] standardModVersion = VersionParser.parseVersion(standardNestedMod.modVersion());
                    Integer[] modpackModVersion = VersionParser.parseVersion(modpackNestedMod.modVersion());
                    if (VersionParser.isGreaterOrEqual(standardModVersion, modpackModVersion)) {
                        continue;
                    }

                    LOGGER.warn("It is indeed conflicting mod versions: {} - {}", standardNestedMod.modVersion(), modpackNestedMod.modVersion());

                    conflictingNestedMods.put(standardNestedMod, modpackNestedMod);
                }
            }
        }

        boolean needsRestart1 = false;

        // create new ignored mods set and place all origin mods of confilcting nested mods
        // entry.modPath returns origin mod path
        Set<String> newIgnoredMods = new HashSet<>(ignoredMods);
        for (LoaderManagerService.Mod entry : conflictingNestedMods.values()) {
            String formattedFile = CustomFileUtils.formatPath(entry.modPath(), modpackDir);
            newIgnoredMods.add(formattedFile);

            Path modPath = entry.modPath();
            Path newModPath = MODS_DIR.resolve(modPath.getFileName());
            if (!CustomFileUtils.compareFileHashes(modPath, newModPath, "SHA-1")) {
                needsRestart1 = true;
                CustomFileUtils.copyFile(modPath, newModPath);

                // find origin mod by path
                LoaderManagerService.Mod originMod = originMods.stream().filter(it -> it.modPath().equals(modPath)).findFirst().orElseThrow();

                // check deps, find dep in modpack mods
                for (String dep : originMod.dependencies()) {
                    LoaderManagerService.Mod depMod = originMods.stream().filter(it -> it.modID().equals(dep)).findFirst().orElse(null);
                    if (depMod == null) {
                        continue;
                    }

                    Path depModPath = depMod.modPath();
                    Path newDepModPath = MODS_DIR.resolve(depModPath.getFileName());
                    if (!CustomFileUtils.compareFileHashes(depModPath, newDepModPath, "SHA-1")) {
                        CustomFileUtils.copyFile(depModPath, newDepModPath);
                    }
                }
            }
        }


        var dupeMods = ModpackUtils.getDupeMods(modpackDir, newIgnoredMods);
        boolean needsRestart2 = ModpackUtils.removeDupeMods(dupeMods);

        return needsRestart1 || needsRestart2;
    }

    public List<LoaderManagerService.Mod> getNestedMods(ModCandidateImpl originMod) {
        List<LoaderManagerService.Mod> mods = new ArrayList<>();

        for (ModCandidateImpl nested : originMod.getNestedMods()) {

            LoaderManager loaderManager = new LoaderManager();
            loaderManager.getMod(nested.getId());
            try {
                String modID = nested.getMetadata().getId();
                Set<String> providesIDs = new HashSet<>(nested.getMetadata().getProvides());
                List<String> dependencies = nested.getMetadata().getDependencies().stream().filter(d -> d.getKind().equals(ModDependency.Kind.DEPENDS)).map(ModDependency::getModId).toList();
                Path originModPath = originMod.getPaths().get(0);

                // replace to universal values we dont need
                LoaderManagerService.Mod mod = new LoaderManagerService.Mod(modID,
                        providesIDs,
                        nested.getMetadata().getVersion().getFriendlyString(),
                        originModPath,
                        LoaderManagerService.EnvironmentType.UNIVERSAL,
                        dependencies
                );

                mods.add(mod);
                List<LoaderManagerService.Mod> recursiveNested = getNestedMods(nested);
                mods.addAll(recursiveNested);
            } catch (Exception ignored) {}
        }

        return mods;
    }

    private Collection<ModCandidateImpl> discoverMods(Path modpackModsDir) throws ModResolutionException, IllegalAccessException, IOException {
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
