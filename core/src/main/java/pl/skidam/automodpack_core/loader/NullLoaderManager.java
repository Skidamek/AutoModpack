package pl.skidam.automodpack_core.loader;

import pl.skidam.automodpack_core.utils.FileInspection;

import java.util.Collection;

public class NullLoaderManager implements LoaderManagerService {
    @Override
    public ModPlatform getPlatformType() {
        return null;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return false;
    }

    @Override
    public Collection<FileInspection.Mod> getModList() {
        return null;
    }

    @Override
    public String getLoaderVersion() {
        return null;
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        return null;
    }

    @Override
    public String getModVersion(String modId) {
        return null;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return false;
    }
}