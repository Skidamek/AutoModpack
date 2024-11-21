package pl.skidam.automodpack_loader_core_forge.loader;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core_forge.mods.LoadedMods;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static pl.skidam.automodpack_core.GlobalVariables.*;

@SuppressWarnings("unused")
public class LoaderManager implements LoaderManagerService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.FORGE;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }

    private Collection<Mod> modList = new ArrayList<>();
    private int lastLoadingModListSize = -1;

    @Override
    public Collection<Mod> getModList() {

        if (preload) {
            return modList;
        }

        List<ModInfo> modInfo = FMLLoader.getLoadingModList().getMods();

        if (!modList.isEmpty() && lastLoadingModListSize == modInfo.size()) {
            // return cached
            return modList;
        }

        lastLoadingModListSize = modInfo.size();
        Collection<Mod> modList = new ArrayList<>();

        for (ModInfo info: modInfo) {
            try {
                String modID = info.getModId();
                List<String> dependencies = info.getDependencies().stream().filter(IModInfo.ModVersion::isMandatory).map(IModInfo.ModVersion::getModId).toList();
                Mod mod = new Mod(modID,
                        List.of(),
                        info.getOwningFile().versionString(),
                        getModPath(modID),
                        getModEnvironment(modID),
                        dependencies
                );
                modList.add(mod);
            } catch (Exception ignored) {}
        }

        return this.modList = modList;
    }

    @Override
    public Mod getMod(String modId) {
        Collection<Mod> modList = getModList();

        if (!modList.isEmpty()) {
            for (Mod mod : getModList()) {
                if (!mod.modID().equals(modId)) {
                    continue;
                }

                return mod;
            }
        }

        Collection<ModFile> modFiles = LoadedMods.INSTANCE.candidateMods;

        for (ModFile mod : modFiles) {
            if (mod.getModFileInfo() == null || mod.getModInfos().isEmpty()) {
                continue;
            }

            if (!mod.getModInfos().get(0).getModId().equals(modId)) {
                continue;
            }

            Path modPath = mod.getModFileInfo().getFile().getFilePath();
            return getMod(modPath);
        }

        return null;
    }

    @Override
    public Mod getMod(Path file) {
        if (!Files.isRegularFile(file)) return null;
        if (!file.getFileName().toString().endsWith(".jar")) return null;

        for (Mod mod : getModList()) {
            if (mod.modPath().toAbsolutePath().equals(file.toAbsolutePath())) {
                return mod;
            }
        }

        // check also out of container
        String modId = getModId(file, true);
        String modVersion = FileInspection.getModVersion(file);
        EnvironmentType environmentType = getModEnvironmentFromNotLoadedJar(file);
        Set<String> dependencies = FileInspection.getModDependencies(file);
        Set<String> providesIDs = FileInspection.getAllProvidedIDs(file);

        if (modId != null && modVersion != null && environmentType != null && dependencies != null) {
            return new Mod(modId, providesIDs, modVersion, file, environmentType, dependencies);
        }

        return null;
    }


    @Override
    public String getLoaderVersion() {
        return FMLLoader.versionInfo().forgeVersion();
    }

    @Override
    public Path getModPath(String modId) {
        if (isDevelopmentEnvironment()) {
            return null;
        }

        try {
            if (preload) {
                Collection<ModFile> modFiles = LoadedMods.INSTANCE.candidateMods;
                for (var modFile: modFiles) {
                    if (modFile.getModFileInfo() == null || modFile.getModInfos().isEmpty()) {
                        continue;
                    }
                    if (modFile.getModInfos().get(0).getModId().equals(modId)) {
                        return modFile.getModInfos().get(0).getOwningFile().getFile().getFilePath().toAbsolutePath();
                    }
                }

            } else if (isModLoaded(modId)) {
                ModFileInfo modInfo = FMLLoader.getLoadingModList().getModFileById(modId);

                List<IModInfo> mods = modInfo.getMods();
                if (!mods.isEmpty()) {
                    return mods.get(0).getOwningFile().getFile().getFilePath().toAbsolutePath();
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        if (FMLLoader.getDist() == Dist.CLIENT) {
            return EnvironmentType.CLIENT;
        } else {
            return EnvironmentType.SERVER;
        }
    }

    @Override
    public EnvironmentType getModEnvironmentFromNotLoadedJar(Path file) {
        // Forge doesnt seem to have a way to get mod environment from jar file
        return EnvironmentType.UNIVERSAL;
    }

    @Override
    public String getModVersion(String modId) {
        if (preload) {
            if (modId.equals("minecraft")) {
                return FMLLoader.versionInfo().mcVersion();
            }

            Collection<ModFile> modFiles = LoadedMods.INSTANCE.candidateMods;

            for (var modFile: modFiles) {
                if (modFile.getModFileInfo() == null || modFile.getModInfos().isEmpty()) {
                    continue;
                }
                if (modFile.getModInfos().get(0).getModId().equals(modId)) {
                    return modFile.getModInfos().get(0).getVersion().toString();
                }
            }

            return null;
        }

        ModInfo modInfo = FMLLoader.getLoadingModList().getMods().stream().filter(mod -> mod.getModId().equals(modId)).findFirst().orElse(null);

        if (modInfo == null) {
            return null;
        }

        return modInfo.getVersion().toString();
    }

    @Override
    public String getModVersion(Path file) {
        return FileInspection.getModVersion(file);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public EnvironmentType getModEnvironment(String modId) {
        return getModEnvironmentFromNotLoadedJar(getModPath(modId));
    }

    public String getModId(Path file, boolean checkAlsoOutOfContainer) {
        try {
            if (FMLLoader.getLoadingModList() != null) {
                List<ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();
                for (ModInfo modInfo : modInfos) {
                    if (modInfo.getOwningFile().getFile().getFilePath().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
                        return modInfo.getModId();
                    }
                }
            }
        } catch (Exception ignored) {}

        if (checkAlsoOutOfContainer) {
            return getModIdFromNotLoadedJar(file);
        }

        return null;
    }

    @Override
    public String getModIdFromNotLoadedJar(Path file) {
        return FileInspection.getModID(file);
    }
}