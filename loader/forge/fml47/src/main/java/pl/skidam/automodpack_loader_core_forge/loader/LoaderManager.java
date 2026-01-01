package pl.skidam.automodpack_loader_core_forge.loader;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.ClientCacheUtils;
import pl.skidam.automodpack_core.utils.FileInspection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

    private Collection<FileInspection.Mod> modList = new ArrayList<>();
    private int lastLoadingModListSize = -1;

    @Override
    public Collection<FileInspection.Mod> getModList() {

        if (preload) {
            return modList;
        }

        List<ModInfo> modInfo = FMLLoader.getLoadingModList().getMods();

        if (!modList.isEmpty() && lastLoadingModListSize == modInfo.size()) {
            // return cached
            return modList;
        }

        lastLoadingModListSize = modInfo.size();
        Collection<FileInspection.Mod> modList = new ArrayList<>();

        for (ModInfo info : modInfo) {
            try {
                String modID = info.getModId();
                Path path = getModPath(modID);
                if (path == null || path.toString().isEmpty()) // If we cant get the path, we skip the mod, its probably JiJed, we dont need it in the list
                    continue;

                if (!Files.exists(path))
                    continue;

                String hash = ClientCacheUtils.computeHashIfNeeded(path);
                if (hash == null)
                    continue;

                List<String> dependencies = info.getDependencies().stream().filter(IModInfo.ModVersion::isMandatory).map(IModInfo.ModVersion::getModId).toList();
                FileInspection.Mod mod = new FileInspection.Mod(
                        modID,
                        hash,
                        List.of(),
                        info.getOwningFile().versionString(),
                        path,
                        EnvironmentType.UNIVERSAL,
                        dependencies);
                modList.add(mod);
            } catch (Exception ignored) {
            }
        }

        return this.modList = modList;
    }

    @Override
    public String getLoaderVersion() {
        return FMLLoader.versionInfo().forgeVersion();
    }

    private Path getModPath(String modId) {
        if (isDevelopmentEnvironment()) {
            return null;
        }

        if (isModLoaded(modId)) {
            ModFileInfo modInfo = FMLLoader.getLoadingModList().getModFileById(modId);

            List<IModInfo> mods = modInfo.getMods();
            if (!mods.isEmpty()) {
                return mods.get(0).getOwningFile().getFile().getFilePath().toAbsolutePath();
            }
        }

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
    public String getModVersion(String modId) {
        if (preload) {
            if (modId.equals("minecraft")) {
                return FMLLoader.versionInfo().mcVersion();
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
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }
}