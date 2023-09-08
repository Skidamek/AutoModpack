package pl.skidam.automodpack_core_forge.loader;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import pl.skidam.automodpack_common.utils.FileInspection;
import pl.skidam.automodpack_core.loader.LoaderService;
import settingdust.preloadingtricks.forge.ForgeLanguageProviderCallback;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
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
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Collection<?> getModList() {
        return FabricLoader.getInstance().getAllMods();
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
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
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
                        if (split[1].replaceAll("\"", "").trim().equalsIgnoreCase("client")) {
                            return EnvironmentType.CLIENT;
                        } else {
                            return EnvironmentType.SERVER;
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

        return FabricLoader.getInstance().getModContainer(modId).isPresent() ? FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getVersion().getFriendlyString() : null;
    }

    @Override
    public String getModVersion(Path file) {
        return FileInspection.getModVersion(file);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public EnvironmentType getModEnvironment(String modId) {
        var container = FabricLoader.getInstance().getModContainer(modId);
        if (container.isEmpty()) {
            return EnvironmentType.UNIVERSAL;
        }
        ModEnvironment env = container.get().getMetadata().getEnvironment();
        if (env == ModEnvironment.CLIENT) {
            return EnvironmentType.CLIENT;
        } else if (env == ModEnvironment.SERVER) {
            return EnvironmentType.SERVER;
        } else {
            return EnvironmentType.UNIVERSAL;
        }
    }

    @Override
    public String getModId(Path file, boolean checkAlsoOutOfContainer) {
        for (ModContainer modContainer : FabricLoader.getInstance().getAllMods()) {
            FileSystem fileSys = modContainer.getRootPaths().get(0).getFileSystem();
            Path modFile = Paths.get(fileSys.toString());
            if (modFile.getFileName().equals(file.getFileName())) {
                return modContainer.getMetadata().getId();
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