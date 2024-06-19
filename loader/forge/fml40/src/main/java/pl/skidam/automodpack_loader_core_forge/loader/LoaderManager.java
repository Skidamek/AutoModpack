package pl.skidam.automodpack_loader_core_forge.loader;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_loader_core_forge.mods.LoadedMods;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.GlobalVariables.preload;

@SuppressWarnings("unused")
public class LoaderManager implements LoaderService {

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
            String modID = info.getModId();
            Path path = getModPath(modID);
            // If we cant get the path, we skip the mod, its probably JiJed, we dont need it in the list
            if (path == null || path.toString().isEmpty()) {
                continue;
            }
            List<String> dependencies = info.getDependencies().stream().filter(IModInfo.ModVersion::isMandatory).map(IModInfo.ModVersion::getModId).toList();
            Mod mod = new Mod(modID,
                    info.getOwningFile().versionString(),
                    path,
                    getModEnvironment(modID),
                    dependencies
            );
            modList.add(mod);
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
        List<String> dependencies = FileInspection.getModDependencies(file);

        if (modId != null && modVersion != null && environmentType != null && dependencies != null) {
            return new Mod(modId, modVersion, file, environmentType, dependencies);
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
        if (file == null || !Files.isRegularFile(file)) {
            return EnvironmentType.UNIVERSAL;
        }

        try {
            if (!Files.isRegularFile(file)) return EnvironmentType.UNIVERSAL;

            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = zipFile.getEntry("META-INF/mods.toml");

            if (entry != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("side")) {
                        continue;
                    }

                    String[] split = line.split("=");
                    if (split.length > 1) {
                        String env = split[1].replaceAll("\"", "").trim();
                        env = env.split(" ")[0];
                        if (env.equalsIgnoreCase("client")) {
                            return EnvironmentType.CLIENT;
                        } else if (env.equalsIgnoreCase("server")) {
                            return EnvironmentType.SERVER;
                        } else {
                            return EnvironmentType.UNIVERSAL;
                        }
                    }
                }
            }
        } catch (ZipException ignored) {
        } catch (Exception e) {
            LOGGER.error("Failed to get mod environment from file: " + file.getFileName() + " - " + file.getFileSystem() + " - " + file.getRoot() + " - " + file.getParent());
            e.printStackTrace();
        }

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
        if (FMLLoader.getLoadingModList() != null) {
            List<ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();
            for (ModInfo modInfo : modInfos) {
                if (modInfo.getOwningFile().getFile().getFilePath().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
                    return modInfo.getModId();
                }
            }
        }

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