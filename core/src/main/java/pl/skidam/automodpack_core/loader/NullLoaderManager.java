package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.Collection;

public class NullLoaderManager implements LoaderService {
    @Override
    public ModPlatform getPlatformType() {
        return null;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return false;
    }

    @Override
    public Collection<Mod> getModList() {
        return null;
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
        return null;
    }

    @Override
    public Path getModPath(String modId) {
        return null;
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        return null;
    }

    @Override
    public EnvironmentType getModEnvironmentFromNotLoadedJar(Path file) {
        return null;
    }

    @Override
    public String getModVersion(String modId) {
        return null;
    }

    @Override
    public String getModVersion(Path file) {
        return null;
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
        return null;
    }
}
