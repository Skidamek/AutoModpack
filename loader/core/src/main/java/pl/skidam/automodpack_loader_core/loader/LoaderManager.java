package pl.skidam.automodpack_loader_core.loader;

import pl.skidam.automodpack_core.loader.LoaderManagerService;

public class LoaderManager implements LoaderManagerService {

    @Override
    public ModPlatform getPlatformType() {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public boolean isModLoaded(String modId) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public String getLoaderVersion() {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public String getModVersion(String modId) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        throw new AssertionError("Loader class not found");
    }
}
