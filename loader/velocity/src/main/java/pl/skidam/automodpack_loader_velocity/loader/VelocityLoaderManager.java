package pl.skidam.automodpack_loader_velocity.loader;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.FileInspection;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

// Test values for now
public class VelocityLoaderManager implements LoaderManagerService {

    @Override
    public ModPlatform getPlatformType() {
        return ModPlatform.FABRIC;
    }

    // No mod will be loaded on velocity
    @Override
    public boolean isModLoaded(String modId) {
        return false;
    }

    @Override
    public Collection<Mod> getModList() {
        return List.of();
    }

    @Override
    public Mod getMod(String modId) {
        return null;
    }

    @Override
    public Mod getMod(Path file) {
        return null;
    }

    @Override
    public String getLoaderVersion() {
        return "3.4.0";
    }

    @Override
    public Path getModPath(String modId) {
        return null;
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        return EnvironmentType.SERVER;
    }

    @Override
    public EnvironmentType getModEnvironmentFromNotLoadedJar(Path file) {
        return FileInspection.getModEnvironment(file);
    }

    @Override
    public String getModVersion(String modId) {
        return null;
    }

    @Override
    public String getModVersion(Path file) {
        return FileInspection.getModVersion(file);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return false;
    }

    @Override
    public EnvironmentType getModEnvironment(String modId) {
        return null;
    }

    @Override
    public String getModIdFromNotLoadedJar(Path file) {
        return FileInspection.getModID(file);
    }
}
