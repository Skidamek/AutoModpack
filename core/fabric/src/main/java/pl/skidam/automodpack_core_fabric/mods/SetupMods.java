package pl.skidam.automodpack_core_fabric.mods;

import net.fabricmc.loader.api.LanguageAdapter;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class SetupMods implements SetupModsService {
    private final FabricLanguageProviderCallback.FabricModSetupService service = FabricLanguageProviderCallback.FabricModSetupService.INSTANCE;
    private final Map<String, Set<ModCandidate>> envDisabledMods = new HashMap<>();
    private Field adapterMapField;
    private Method addModMethod;
    private Method addCandidateFinderMethod;

    @Override
    public void run(Path modpack) {
        if (modpack == null || !Files.isDirectory(modpack)) {
            LOGGER.warn("Incorrect path to modpack");
            return;
        }

        modpack = modpack.toAbsolutePath();

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

            List<ModCandidate> candidates;
            candidates = (List<ModCandidate>) discoverMods(modpack);
            candidates = (List<ModCandidate>) resolveMods(candidates);

            LOGGER.info(
                "Loading {} modpack mod{}{}",
                candidates.size(),
                candidates.size() > 1 ? "s" : "",
                candidates.isEmpty() ? "" : ":\n " + String.join("\n", candidates.stream().map(it -> "\t- " + it.getId() + " " + it.getVersion().getFriendlyString()).toArray(String[]::new))
            );

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
                new FilteredDirectoryModCandidateFinder(
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
        Map<String, LanguageAdapter> adapterMap = (Map<String, LanguageAdapter>) adapterMapField.get(FabricLoaderImpl.INSTANCE);
        for (ModCandidate candidate : candidates) {

            Map<String, String> definitions = candidate.getMetadata().getLanguageAdapterDefinitions();
            if (definitions.isEmpty()) {
                continue;
            }

            LOGGER.debug("Setting up language adapter for {}", candidate.getId());

            for (Map.Entry<String, String> entry : definitions.entrySet()) {

                if (adapterMap.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException("Duplicate language adapter ID: " + entry.getKey());
                }

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
