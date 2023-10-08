package pl.skidam.automodpack_core_fabric.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import pl.skidam.automodpack_core.loader.LoaderService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static pl.skidam.automodpack_common.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_common.GlobalVariables.modsPath;

public class LoaderManager implements LoaderService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.FABRIC;
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
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("fabricloader");
        return modContainer.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    @Override
    public Path getModPath(String modId) {
        if (isModLoaded(modId)) {
            for (ModContainer modContainer : FabricLoader.getInstance().getAllMods()) {
                FileSystem fileSys = modContainer.getRootPaths().get(0).getFileSystem();
                Path modFile = Paths.get(fileSys.toString());
                if (modContainer.getMetadata().getId().equals(modId)) {
                    return modFile;
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
        if (!Files.isRegularFile(file)) return null;
        if (!file.getFileName().endsWith(".jar")) return null;

        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            }

            if (entry != null) {
                Gson gson = new Gson();
                InputStream stream = zipFile.getInputStream(entry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                JsonObject json = gson.fromJson(reader, JsonObject.class);

                // close everything
                reader.close();
                stream.close();
                zipFile.close();

                if (json.has("environment")) {
                    String env = json.get("environment").getAsString();
                    if (env.equalsIgnoreCase("client")) {
                        return EnvironmentType.CLIENT;
                    } else if (env.equalsIgnoreCase("server")) {
                        return EnvironmentType.SERVER;
                    } else {
                        return EnvironmentType.UNIVERSAL;
                    }
                }
            }
        } catch (ZipException ignored) {
        } catch (IOException e) {
            LOGGER.error("Failed to get mod env from file: " + file.getFileName());
            e.printStackTrace();
        }

        return EnvironmentType.UNIVERSAL;
    }

    @Override
    public String getModVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).isPresent() ? FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getVersion().getFriendlyString() : null;
    }

    @Override
    public String getModVersion(Path file) {
        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            }

            if (entry != null) {
                Gson gson = new Gson();
                InputStream stream = zipFile.getInputStream(entry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                JsonObject json = gson.fromJson(reader, JsonObject.class);

                // close everything
                reader.close();
                stream.close();
                zipFile.close();

                if (json.has("version")) {
                    return json.get("version").getAsString();
                }
            }
        } catch (ZipException ignored) {
            return "UNKNOWN";
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.error("Failed to get mod version from file: " + file.getFileName());
            e.printStackTrace();
        }

        return "UNKNOWN";
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
            if (modFile.toAbsolutePath().equals(file.toAbsolutePath())) {
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
        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            }

            if (entry != null) {
                Gson gson = new Gson();
                InputStream stream = zipFile.getInputStream(entry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                JsonObject json = gson.fromJson(reader, JsonObject.class);

                // close everything
                reader.close();
                stream.close();
                zipFile.close();

                if (json.has("id")) {
                    return json.get("id").getAsString();
                }
            }
        } catch (ZipException ignored) {
            return null;
        } catch (IOException e) {
            LOGGER.error("Failed to get mod id from file: " + file.getFileName());
            e.printStackTrace();
            return null;
        }

        return null;
    }
}
