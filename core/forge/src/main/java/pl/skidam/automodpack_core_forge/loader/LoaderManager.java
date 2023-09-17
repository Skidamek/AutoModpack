package pl.skidam.automodpack_core_forge.loader;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import pl.skidam.automodpack_common.utils.FileInspection;
import pl.skidam.automodpack_core.loader.LoaderService;
import settingdust.preloadingtricks.forge.ForgeLanguageProviderCallback;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class LoaderManager implements LoaderService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.FORGE;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Collection<?> getModList() {

        List <ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();
        List <String> modList = new ArrayList<>();

        for (ModInfo modInfo: modInfos) {
            modList.add(modInfo.getModId() + " " + modInfo.getVersion().toString()); // fabric like mod list
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
            Collection <ModFile> modFiles = ForgeLanguageProviderCallback.ForgeModSetupService.INSTANCE.all();
            for (ModFile modFile: modFiles) {
                if (modFile.getModInfos().get(0).getModId().equals(modId)) {
                    return modFile.getModInfos().get(0).getOwningFile().getFile().getFilePath();
                }
            }

        } else {

            if (isModLoaded(modId)) {

                if (!Files.exists(modsPath)) {
                    LOGGER.error("Could not find mods folder!?");
                    return null;
                }

                try {
                    Path[] mods = Files.list(modsPath).toArray(Path[]::new);
                    for (Path mod : mods) {
                        if (mod.getFileName().toString().endsWith(".jar")) {
                            String modIdFromLoadedJar = getModId(mod, false);
                            if (modIdFromLoadedJar != null && modIdFromLoadedJar.equals(modId)) {
                                return mod;
                            }
                        }
                    }
                } catch (IOException e) {
                    LOGGER.error("Could not get mod path for " + modId);
                    e.printStackTrace();
                }
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
            LOGGER.error("Failed to get mod environment from file: " + file);
            e.printStackTrace();
        }

        return EnvironmentType.UNIVERSAL;
    }

    @Override
    public String getModVersion(String modId) {
        if (preload) {
            Collection <ModFile> modFiles = ForgeLanguageProviderCallback.ForgeModSetupService.INSTANCE.all();

            for (ModFile modFile: modFiles) {
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
}