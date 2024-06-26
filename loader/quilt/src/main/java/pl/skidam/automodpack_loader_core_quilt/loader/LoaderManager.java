package pl.skidam.automodpack_loader_core_quilt.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import org.quiltmc.loader.api.*;
import org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static pl.skidam.automodpack_core.GlobalVariables.*;

@SuppressWarnings("unused")
public class LoaderManager implements LoaderService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.QUILT;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return QuiltLoader.isModLoaded(modId);
    }

    private Collection<Mod> modList = new ArrayList<>();
    private int lastLoadingModListSize = -1;

    @Override
    public Collection<Mod> getModList() {

        Collection <ModContainer> mods = QuiltLoader.getAllMods();

        if (!modList.isEmpty() && lastLoadingModListSize == mods.size()) {
            return modList;
        }

        lastLoadingModListSize = mods.size();
        Collection<Mod> modList = new ArrayList<>();

        for (var info : mods) {
            String modID = info.metadata().id();
            Path path = getModPath(modID);
            List<String> provideIDs = info.metadata().provides().stream().map(ModMetadata.ProvidedMod::id).toList();
            // If we cant get the path, we skip the mod, its probably JiJed, we dont need it in the list
            if (path == null || path.toString().isEmpty()) {
                continue;
            }
            List<String> dependencies = info.metadata().depends().stream().map(d -> {
                if (d instanceof ModDependency.Only only) {
                    return only.id().id(); // what the heck
                }
                return null;
            }).filter(Objects::nonNull).toList();
            Mod mod = new Mod(modID,
                    provideIDs,
                    info.metadata().version().raw(),
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
        for (Mod mod : getModList()) {
            if (mod.modID().equals(modId)) {
                return mod;
            }
        }
        return null;
    }

    @Override
    public Mod getMod(Path file) {
        if (!Files.isRegularFile(file)) return null;
        if (!file.getFileName().toString().endsWith(".jar")) return null;

        for (Mod mod : getModList()) {
            if (mod.modPath().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
                return mod;
            }
        }

        // check also out of container
        String modId = getModId(file, true);
        String modVersion = FileInspection.getModVersion(file);
        EnvironmentType environmentType = getModEnvironmentFromNotLoadedJar(file);
        List<String> dependencies = FileInspection.getModDependencies(file);
        List<String> providesIDs = FileInspection.getAllProvidedIDs(file);

        if (modId != null && modVersion != null && environmentType != null && dependencies != null) {
            return new Mod(modId, providesIDs, modVersion, file, environmentType, dependencies);
        }

        return null;
    }


    @Override
    public String getLoaderVersion() {
        ModContainer container = QuiltLoader.getModContainer("quilt_loader").isPresent() ? QuiltLoader.getModContainer("quilt_loader").get() : null;
        return container != null ? container.metadata().version().raw() : null;
    }

    @Override
    public Path getModPath(String modId) {
        ModContainer container = QuiltLoader.getModContainer(modId).isPresent() ? QuiltLoader.getModContainer(modId).get() : null;

        if (container != null && container.getSourcePaths().stream().findFirst().isPresent()) {
            for (Path path: container.getSourcePaths().stream().findFirst().get()) {
                if (path == null) {
                    continue;
                }

                return path;
            }
        }

        LOGGER.error("Could not find jar file for " + modId);
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
        if (!file.getFileName().toString().endsWith(".jar")) return null;

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
                    return EnvironmentType.UNIVERSAL;
                }

                if (env.equalsIgnoreCase("client")) {
                    return EnvironmentType.CLIENT;
                } else if (env.equalsIgnoreCase("server")) {
                    return EnvironmentType.SERVER;
                } else {
                    return EnvironmentType.UNIVERSAL;
                }
            }
        } catch (ZipException ignored) {
        } catch (IOException e) {
            e.printStackTrace();
        }

        return EnvironmentType.UNIVERSAL;
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
            } else {
                if (loaderValue.asString().equalsIgnoreCase("client")) {
                    return EnvironmentType.CLIENT;
                } else if (loaderValue.asString().equalsIgnoreCase("server")) {
                    return EnvironmentType.SERVER;
                } else {
                    return EnvironmentType.UNIVERSAL;
                }
            }
        }
        return EnvironmentType.UNIVERSAL;
    }


    public String getModId(Path file, boolean checkAlsoOutOfContainer) {
        if (!Files.isRegularFile(file)) return null;
        if (!file.getFileName().toString().endsWith(".jar")) return null;
        if (getModEnvironmentFromNotLoadedJar(file).equals(EnvironmentType.UNIVERSAL)) return null;

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
