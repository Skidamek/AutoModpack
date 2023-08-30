package pl.skidam.automodpack_core.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import pl.skidam.automodpack_core.loader.LoaderService;
import settingdust.preloadingtricks.forge.ForgeLanguageProviderCallback;

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

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class ForgeLoaderImpl implements LoaderService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.FORGE;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Collection getModList() {
        List <ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();

        List <String> modList = new ArrayList<>();

        for (ModInfo modInfo: modInfos) {
            modList.add(modInfo.getModId() + " " + modInfo.getVersion().toString());
        }

        return modList;
    }

    @Override
    public String getLoaderVersion() {
        return FMLLoader.versionInfo().forgeVersion();
    }

    @Override
    public Path getModPath(String modId) {
        if (preload) {
            Collection <ModFile> modFiles = ForgeLanguageProviderCallback.ForgeModSetupService.INSTANCE.all();
            for (ModFile modFile: modFiles) {
                if (modFile.getModInfos().get(0).getModId().equals(modId)) {
                    return modFile.getModInfos().get(0).getOwningFile().getFile().getFilePath();
                }
            }

        } else {
            List <ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();

            for (ModInfo modInfo: modInfos) {
                if (modInfo.getModId().equals(modId)) {
                    return modInfo.getOwningFile().getFile().getFilePath();
                }
            }
        }

        return null;
    }

    @Override
    public String getEnvironmentType() {
        Dist dist = FMLLoader.getDist();
        if (dist.isClient()) {
            return "CLIENT";
        } else if (dist.isDedicatedServer()) {
            return "SERVER";
        } else {
            return "UNKNOWN";
        }
    }

    @Override
    public String getModEnvironmentFromNotLoadedJar(Path file) {
        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = zipFile.getEntry("META-INF/mods.toml");

            if (entry != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("modId")) {
                        String[] split = line.split("=");
                        if (split.length > 1) {
                            return split[1].replaceAll("\"", "").trim();
                        }
                    }
                }
            }
        } catch (ZipException ignored) {
            return null;
        } catch (Exception e) {
            LOGGER.error("Failed to get mod id from file: " + file.getFileName());
            e.printStackTrace();
            return null;
        }

        return null;
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
        } else {

            ModInfo modInfo = FMLLoader.getLoadingModList().getMods().stream().filter(mod -> mod.getModId().equals(modId)).findFirst().orElse(null);

            if (modInfo == null) {
                return null;
            }

            return modInfo.getVersion().toString();
        }

        return null;
    }

    @Override
    public String getModVersion(Path file) {
        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry modsTomlEntry = zipFile.getEntry("META-INF/mods.toml");

            if (modsTomlEntry != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(modsTomlEntry)));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("version")) {
                        String[] split = line.split("=");
                        if (split.length > 1) {
                            String version = split[1].substring(0, split[1].lastIndexOf("\""));
                            version = version.replaceAll("\"", "").trim();

                            if ("${file.jarVersion}".equals(version)) {
                                ZipEntry manifestEntry = zipFile.getEntry("META-INF/MANIFEST.MF");
                                if (manifestEntry != null) {
                                    BufferedReader manifestReader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(manifestEntry)));
                                    String manifestLine;
                                    while ((manifestLine = manifestReader.readLine()) != null) {
                                        if (manifestLine.startsWith("Implementation-Version")) {
                                            String[] manifestSplit = manifestLine.split(":");
                                            if (manifestSplit.length > 1) {
                                                version = manifestSplit[1].trim();
                                            }
                                        }
                                    }
                                }
                            }

                            return version;
                        }
                    }
                }
            }
        } catch (ZipException ignored) {
            return "UNKNOWN";
        } catch (Exception e) {
            LOGGER.error("Failed to get mod version from file: " + file.getFileName());
            e.printStackTrace();
        }

        return "UNKNOWN";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public String getModEnvironment(String modId) {
        List <ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();

        try {
            for (ModInfo modInfo: modInfos) {
                if (modInfo.getModId().equals(modId)) {
                    Path file = modInfo.getOwningFile().getFile().getFilePath().toAbsolutePath().normalize();
                    if (file.toFile().exists() && Files.isRegularFile(file)) {
                        return getModEnvironmentFromNotLoadedJar(file);
                    }
                }
            }
        } catch (UnsupportedOperationException ignored) {

        }

        return "UNKNOWN";
    }

    @Override
    public String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
        List <ModInfo> modInfos = FMLLoader.getLoadingModList().getMods();

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
        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = zipFile.getEntry("META-INF/mods.toml");

            if (entry != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("side")) {
                        String[] split = line.split("=");
                        if (split.length > 1) {
                            return split[1].replaceAll("\"", "").trim();
                        }
                    }
                }
            }
        } catch (ZipException ignored) {
            return "UNKNOWN";
        } catch (Exception e) {
            LOGGER.error("Failed to get mod environment from file: " + file);
            e.printStackTrace();
        }

        return "UNKNOWN";
    }
}