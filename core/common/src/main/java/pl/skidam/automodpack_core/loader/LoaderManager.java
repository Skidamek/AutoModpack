package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.Collection;
import java.util.ServiceLoader;

public class LoaderManager implements LoaderService {

    private LoaderService getServiceLoader() {
        ServiceLoader<LoaderService> loaderServiceLoader = ServiceLoader.load(LoaderService.class);
        for (LoaderService loaderService : loaderServiceLoader) {
            return loaderService;
        }

        throw new AssertionError("No loader service found");
    }

    @Override
    public ModPlatform getPlatformType() {
        return getServiceLoader().getPlatformType();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return getServiceLoader().isModLoaded(modId);
    }

    @Override
    public Collection getModList() {
        return getServiceLoader().getModList();
    }

    @Override
    public String getLoaderVersion() {
        return getServiceLoader().getLoaderVersion();
    }

    @Override
    public Path getModPath(String modId) {
        return getServiceLoader().getModPath(modId);
    }

    @Override
    public String getEnvironmentType() {
        return getServiceLoader().getEnvironmentType();
    }

    @Override
    public String getModEnvironmentFromNotLoadedJar(Path file) {
        return getServiceLoader().getModEnvironmentFromNotLoadedJar(file);
    }

    @Override
    public String getModVersion(String modId) {
        return getServiceLoader().getModVersion(modId);
    }

    @Override
    public String getModVersion(Path file) {
        return getServiceLoader().getModVersion(file);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return getServiceLoader().isDevelopmentEnvironment();
    }

    @Override
    public String getModEnvironment(String modId) {
        return getServiceLoader().getModEnvironment(modId);
    }

    @Override
    public String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
        return getServiceLoader().getModIdFromLoadedJar(file, checkAlsoOutOfContainer);
    }

    @Override
    public String getModIdFromNotLoadedJar(Path file) {
        return getServiceLoader().getModIdFromNotLoadedJar(file);
    }
}
