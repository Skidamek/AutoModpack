package pl.skidam.automodpack_loader_core_forge.loader;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_loader_core.loader.LoaderService;
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

import static pl.skidam.automodpack_core.GlobalVariables.*;

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

    @Override
    public Collection<Mod> getModList() {

        Collection<ModInfo> modInfo = FMLLoader.getLoadingModList().getMods();
        Collection<Mod> modList = new ArrayList<>();

        for (ModInfo info: modInfo) {
            String modID = info.getModId();
            Mod mod = new Mod(modID,
                    info.getOwningFile().versionString(),
                    getModPath(modID),
                    isJiJedMod(modID),
                    getModEnvironment(modID)
                    // TODO change it to mod specific platform
            );
            modList.add(mod);
        }

        return modList;
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
            Collection<IModFile> modFiles = LoadedMods.INSTANCE.all();
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
        if (file == null || !Files.exists(file)) {
            return EnvironmentType.UNIVERSAL;
        }

        try {
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
            Collection<IModFile> modFiles = LoadedMods.INSTANCE.all();

            for (var modFile: modFiles) {
                if (modFile.getModFileInfo() == null || modFile.getModInfos().isEmpty()) {
                    continue;
                }
                if (modFile.getModInfos().get(0).getModId().equals(modId)) {
                    return modFile.getModInfos().get(0).getVersion().toString();
                }
            }
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
        if (isJiJedMod(modId)) {
            // It doesn't really matter, but change it to correct one in the future
            return EnvironmentType.UNIVERSAL;
        }
        return getModEnvironmentFromNotLoadedJar(getModPath(modId));
    }

    @Override
    public String getModId(Path file, boolean checkAlsoOutOfContainer) {
        List<ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();

        for (ModInfo modInfo: modInfos) {
            if (modInfo.getOwningFile().getFile().getFilePath().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
                return modInfo.getModId();
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

    @Override
    public boolean isJiJedMod(String modId) {
        Path modPath = getModPath(modId);
        String modName = modPath.getFileName().toString();
        return !modPath.toString().endsWith(modName);
    }
}