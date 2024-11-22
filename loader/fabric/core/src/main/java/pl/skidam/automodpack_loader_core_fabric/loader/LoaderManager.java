package pl.skidam.automodpack_loader_core_fabric.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.FileInspection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

@SuppressWarnings("unused")
public class LoaderManager implements LoaderManagerService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.FABRIC;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    private Collection<Mod> modList = new ArrayList<>();
    private int lastLoadingModListSize = -1;

    @Override
    public Collection<Mod> getModList() {

        Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();

        if (!modList.isEmpty() && lastLoadingModListSize == mods.size()) {
            return modList;
        }

        lastLoadingModListSize = mods.size();
        Collection<Mod> modList = new ArrayList<>();

        for (var info : mods) {
            try {
                String modID = info.getMetadata().getId();
                Path path = getModPath(modID);
                // If we cant get the path, we skip the mod, its probably JiJed, we dont need it in the list
                if (path == null || path.toString().isEmpty()) {
                    continue;
                }

                Set<String> providesIDs = new HashSet<>(info.getMetadata().getProvides());
                List<String> dependencies = info.getMetadata().getDependencies().stream().filter(d -> d.getKind().equals(ModDependency.Kind.DEPENDS)).map(ModDependency::getModId).toList();

                Mod mod = new Mod(modID,
                        providesIDs,
                        info.getMetadata().getVersion().getFriendlyString(),
                        path,
                        getModEnvironment(modID),
                        dependencies
                );

                modList.add(mod);
            } catch (Exception ignored) {}
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
            if (mod.modPath().toAbsolutePath().equals(file.toAbsolutePath())) {
                return mod;
            }
        }

        // check also out of container
        String modId = FileInspection.getModID(file);
        String modVersion = FileInspection.getModVersion(file);
        EnvironmentType environmentType = getModEnvironmentFromNotLoadedJar(file);
        Set<String> dependencies = FileInspection.getModDependencies(file);
        Set<String> providesIDs = FileInspection.getAllProvidedIDs(file);

        if (modId != null && modVersion != null && environmentType != null && dependencies != null) {
            return new Mod(modId, providesIDs, modVersion, file, environmentType, dependencies);
        }

        return null;
    }

    @Override
    public String getLoaderVersion() {
        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer("fabricloader");
        return modContainer.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    @Override
    public Path getModPath(String modId) {
        if (!isModLoaded(modId)) return null;

        try {
            for (ModContainer modContainer : FabricLoader.getInstance().getAllMods()) {
                if (modContainer.getMetadata().getId().equals(modId)) {
                    FileSystem fileSys = modContainer.getRootPaths().get(0).getFileSystem();
                    return Path.of(fileSys.toString());
                }
            }
        } catch (Exception ignored) {}

        LOGGER.error("Could not find jar file for {}", modId);
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
        if (!Files.exists(file)) return null;
        if (!file.getFileName().toString().endsWith(".jar")) return null;

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
            LOGGER.error("Failed to get mod env from file: {} {}", file.getFileName(), e);
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
            LOGGER.error("Failed to get mod version from file: {} {}", file.getFileName(), e);
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

    public String getModId(Path file, boolean checkAlsoOutOfContainer) {
        try {
            for (ModContainer modContainer : FabricLoader.getInstance().getAllMods()) {
                FileSystem fileSys = modContainer.getRootPaths().get(0).getFileSystem();
                Path modFile = Paths.get(fileSys.toString());
                if (modFile.toAbsolutePath().equals(file.toAbsolutePath())) {
                    return modContainer.getMetadata().getId();
                }
            }
        } catch (Exception ignored) {}

        if (checkAlsoOutOfContainer) {
            return getModIdFromNotLoadedJar(file);
        }

        return null;
    }

    @Override
    public String getModIdFromNotLoadedJar(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;

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
            LOGGER.error("Failed to get mod id from file: {} {}", file.getFileName(), e);
        }

        return null;
    }
}
