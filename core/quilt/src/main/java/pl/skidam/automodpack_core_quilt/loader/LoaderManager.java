package pl.skidam.automodpack_core_quilt.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.quiltmc.loader.api.LoaderValue;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader;
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
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class LoaderManager implements LoaderService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.QUILT;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return QuiltLoader.isModLoaded(modId);
    }

    @Override
    public Collection<?> getModList() {
        Collection <ModContainer> modsList = QuiltLoader.getAllMods();

        return modsList.stream().map(mod -> mod.metadata().id() + " " + mod.metadata().version()).collect(Collectors.toList());
    }

    @Override
    public String getLoaderVersion() {
        ModContainer container = QuiltLoader.getModContainer("quilt_loader").isPresent() ? QuiltLoader.getModContainer("quilt_loader").get() : null;
        return container != null ? container.metadata().version().raw() : null;
    }

    @Override
    public Path getModPath(String modId) {
        ModContainer container = QuiltLoader.getModContainer(modId).isPresent() ? QuiltLoader.getModContainer(modId).get() : null;

        if (container != null) {
            for (Path path: Objects.requireNonNull(container.getSourcePaths().stream().findFirst().isPresent() ? container.getSourcePaths().stream().findFirst().get() : null)) {
                if (path == null) {
                    LOGGER.error("Could not find jar file for " + modId);
                    return null;
                }

                return path;
            }
        }
        return null;
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        if (MinecraftQuiltLoader.getEnvironmentType() == EnvType.CLIENT) {
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
            if (zipFile.getEntry("quilt.mod.json") != null) {
                entry = zipFile.getEntry("quilt.mod.json");
            } else if (zipFile.getEntry("fabric.mod.json") != null) {
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

                String env = null;

                if (entry.getName().equals("fabric.mod.json")) {
                    if (json.has("environment")) {
                        env = json.get("environment").getAsString();
                    }
                } else if (json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    if (quiltLoader.has("environment")) {
                        env = quiltLoader.get("environment").getAsString();
                    }

                    // if not, we also can check in different place
                    if (json.has("minecraft")) {
                        JsonObject minecraft = json.get("minecraft").getAsJsonObject();
                        if (minecraft.has("environment")) {
                            env = minecraft.get("environment").getAsString();
                        }
                    }
                }

                if (env == null) {
                    return EnvironmentType.BOTH;
                }

                if (env.equalsIgnoreCase("client")) {
                    return EnvironmentType.CLIENT;
                } else {
                    return EnvironmentType.SERVER;
                }
            }
        } catch (ZipException ignored) {
        } catch (IOException e) {
            e.printStackTrace();
        }

        return EnvironmentType.BOTH;
    }

    @Override
    public String getModVersion(String modId) {
        return QuiltLoader.getModContainer(modId).isPresent() ? QuiltLoader.getModContainer(modId).get().metadata().version().toString() : null;
    }

    @Override
    public String getModVersion(Path file) {
        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = null;
            if (zipFile.getEntry("quilt.mod.json") != null) {
                entry = zipFile.getEntry("quilt.mod.json");
            } else if (zipFile.getEntry("fabric.mod.json") != null) {
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

                if (entry.getName().equals("fabric.mod.json")) {
                    if (json.has("version")) {
                        return json.get("version").getAsString();
                    }
                } else if (json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    if (quiltLoader.has("version")) {
                        return quiltLoader.get("version").getAsString();
                    }
                }
            }
        } catch (ZipException ignored) {
            return null;
        } catch (IOException | JsonSyntaxException e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return QuiltLoader.isDevelopmentEnvironment();
    }

    @Override
    public EnvironmentType getModEnvironment(String modId) {
        if (QuiltLoader.getModContainer(modId).isPresent()) {
            LoaderValue loaderValue = QuiltLoader.getModContainer(modId).get().metadata().value("environment");
            if (loaderValue == null) {
                var container = FabricLoader.getInstance().getModContainer(modId);
                if (container.isEmpty()) {
                    return EnvironmentType.BOTH;
                }
                String env = container.get().getMetadata().getEnvironment().toString();
                if (env.equalsIgnoreCase("client")) {
                    return EnvironmentType.CLIENT;
                } else {
                    return EnvironmentType.SERVER;
                }
            } else {
                if (loaderValue.asString().equalsIgnoreCase("client")) {
                    return EnvironmentType.CLIENT;
                } else {
                    return EnvironmentType.SERVER;
                }
            }
        }
        return EnvironmentType.BOTH;
    }

    @Override
    public String getModId(Path file, boolean checkAlsoOutOfContainer) {
        if (!Files.isRegularFile(file)) return null;
        if (!file.getFileName().endsWith(".jar")) return null;
        if (getModEnvironmentFromNotLoadedJar(file).equals(EnvironmentType.BOTH)) return null;

        for (ModContainer modContainer: QuiltLoader.getAllMods()) {
            FileSystem fileSys = modContainer.rootPath().getFileSystem();
            Path modFile = Paths.get(fileSys.toString());
            if (modFile.getFileName().equals(file.getFileName())) {
                return modContainer.metadata().id();
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
            if (zipFile.getEntry("quilt.mod.json") != null) {
                entry = zipFile.getEntry("quilt.mod.json");
            } else if (zipFile.getEntry("fabric.mod.json") != null) {
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

                if (entry.getName().equals("fabric.mod.json")) {
                    if (json.has("id")) {
                        return json.get("id").getAsString();
                    }
                } else if (json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    if (quiltLoader.has("id")) {
                        return quiltLoader.get("id").getAsString();
                    }
                }
            }
        } catch (ZipException ignored) {
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        return null;
    }
}
