package pl.skidam.automodpack_core.fabric;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import pl.skidam.automodpack_core.LoaderService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class FabricLoaderImpl implements LoaderService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.FABRIC;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Collection getModList() {
        return FabricLoader.getInstance().getAllMods();
    }

    @Override
    public String getLoaderVersion() {
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("fabricloader");
        return modContainer.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse(null);

    }

    @Override
    public Path getModPath(String modId) {
        if (isDevelopmentEnvironment()) {
            return null;
        }

        if (isModLoaded(modId)) {

            if (!Files.exists(modsPath)) {
                LOGGER.error("Could not find mods folder!?");
                return null;
            }

            try {
                Path[] mods = Files.list(modsPath).toArray(Path[]::new);
                for (Path mod : mods) {
                    if (mod.getFileName().toString().endsWith(".jar")) {
                        String modIdFromLoadedJar = getModIdFromLoadedJar(mod, false);
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

        return null;
    }

    @Override
    public String getEnvironmentType() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            return "CLIENT";
        } else if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            return "SERVER";
        } else {
            return "UNKNOWN";
        }
    }

    @Override
    public String getModEnvironmentFromNotLoadedJar(Path file) {
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
                    return json.get("environment").getAsString().toUpperCase();
                }
            }
        } catch (ZipException ignored) {
            return "UNKNOWN";
        } catch (IOException e) {
            LOGGER.error("Failed to get mod env from file: " + file.getFileName());
            e.printStackTrace();
        }

        return "UNKNOWN";
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
    public String getModEnvironment(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).isPresent() ?  FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getEnvironment().toString().toUpperCase() : "*";
    }

    @Override
    public String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
        for (ModContainer modContainer : FabricLoader.getInstance().getAllMods()) {
            FileSystem fileSys = modContainer.getRootPaths().get(0).getFileSystem();
            Path modFile = Paths.get(fileSys.toString());
            if (modFile.getFileName().equals(file.getFileName())) {
                return modContainer.getMetadata().getId();
            }
        }

        if (checkAlsoOutOfContainer) return getModIdFromNotLoadedJar(file);

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
