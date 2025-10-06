package pl.skidam.automodpack_loader_core_neoforge.loader;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static pl.skidam.automodpack_core.GlobalVariables.*;

@SuppressWarnings("unused")
public class LoaderManager implements LoaderManagerService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.NEOFORGE;
    }

    @Override
    public boolean isModLoaded(String modId) {
        LoadingModList loadingModList;
        try {
            loadingModList= FMLLoader.getCurrent().getLoadingModList();
        } catch (IllegalStateException e) {
            return false;
        }
        return loadingModList.getModFileById(modId) != null;
    }

    private Collection<FileInspection.Mod> modList = new ArrayList<>();
    private int lastLoadingModListSize = -1;

    // Does it even still make sense to have this? FileInspection class should do everything anyway
    @Override
    public Collection<FileInspection.Mod> getModList() {

        if (preload) { // always empty on preload
            return modList;
        }

        List<ModInfo> modInfo = FMLLoader.getCurrent().getLoadingModList().getMods();

        if (!modList.isEmpty() && lastLoadingModListSize == modInfo.size()) {
            // return cached
            return modList;
        }

        lastLoadingModListSize = modInfo.size();
        Collection<FileInspection.Mod> modList = new ArrayList<>();

        for (ModInfo info: modInfo) {
            try {
                String modID = info.getModId();
                Path path = getModPath(modID);
                if (path == null || path.toString().isEmpty()) // If we cant get the path, we skip the mod, its probably JiJed, we dont need it in the list
                    continue;

                String hash = CustomFileUtils.getHash(path);
                if (hash == null)
                    continue;

                List<String> dependencies = info.getDependencies().stream().filter(d -> d.getType() == IModInfo.DependencyType.REQUIRED).map(IModInfo.ModVersion::getModId).toList();
                FileInspection.Mod mod = new FileInspection.Mod(
                        modID,
                        hash,
                        List.of(),
                        info.getOwningFile().versionString(),
                        path,
                        EnvironmentType.UNIVERSAL,
                        dependencies);

                modList.add(mod);
            } catch (Exception ignored) {}
        }

        return this.modList = modList;
    }

    @Override
    public String getLoaderVersion() {
        return FMLLoader.getCurrent().getVersionInfo().neoForgeVersion();
    }

    private Path getModPath(String modId) {
        if (isDevelopmentEnvironment()) {
            return null;
        }

        if (isModLoaded(modId)) {
            ModFileInfo modInfo = FMLLoader.getCurrent().getLoadingModList().getModFileById(modId);

            List<IModInfo> mods = modInfo.getMods();
            if (!mods.isEmpty()) {
                return mods.getFirst().getOwningFile().getFile().getFilePath().toAbsolutePath();
            }
        }

        return null;
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        if (FMLLoader.getCurrent().getDist() == Dist.CLIENT) {
            return EnvironmentType.CLIENT;
        } else {
            return EnvironmentType.SERVER;
        }
    }

    @Override
    public String getModVersion(String modId) {
        if (preload) {
            if (modId.equals("minecraft")) {
                return FMLLoader.getCurrent().getVersionInfo().mcVersion();
            }

            return null;
        }

        ModInfo modInfo = FMLLoader.getCurrent().getLoadingModList().getMods().stream().filter(mod -> mod.getModId().equals(modId)).findFirst().orElse(null);

        if (modInfo == null) {
            return null;
        }

        return modInfo.getVersion().toString();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.getCurrent().isProduction();
    }
}