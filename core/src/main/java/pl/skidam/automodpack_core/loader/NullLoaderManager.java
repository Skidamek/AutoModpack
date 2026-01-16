package pl.skidam.automodpack_core.loader;

// TODO remove this, wtf is this even for
public class NullLoaderManager implements LoaderManagerService {
    @Override
    public ModPlatform getPlatformType() {
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