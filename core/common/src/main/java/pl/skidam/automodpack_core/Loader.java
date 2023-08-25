package pl.skidam.automodpack_core;

import java.nio.file.Path;
import java.util.Collection;
import java.util.ServiceLoader;

public class Loader implements LoaderService {

    private LoaderService getServiceLoader() {
        ServiceLoader<LoaderService> loaderServiceLoader = ServiceLoader.load(LoaderService.class);
        for (LoaderService loaderService : loaderServiceLoader) {
            return loaderService;
        }

        throw new AssertionError("No loader service found");
    }

    @Override
    public ModPlatform getPlatformType() {
        getServiceLoader().getPlatformType();

        throw new AssertionError("No loader service found");
    }

    @Override
    public boolean isModLoaded(String modId) {
        getServiceLoader().isModLoaded(modId);

        throw new AssertionError("No loader service found");
    }

    @Override
    public Collection getModList() {
        getServiceLoader().getModList();

        throw new AssertionError("No loader service found");
    }

    @Override
    public String getLoaderVersion() {
        getServiceLoader().getLoaderVersion();

        throw new AssertionError("No loader service found");
    }

    @Override
    public Path getModPath(String modId) {
        getServiceLoader().getModPath(modId);

        throw new AssertionError("No loader service found");
    }

    @Override
    public String getEnvironmentType() {
        getServiceLoader().getEnvironmentType();

        throw new AssertionError("No loader service found");
    }

    @Override
    public String getModEnvironmentFromNotLoadedJar(Path file) {
        getServiceLoader().getModEnvironmentFromNotLoadedJar(file);

        throw new AssertionError("No loader service found");
    }

    @Override
    public String getModVersion(String modId) {
        getServiceLoader().getModVersion(modId);

        throw new AssertionError("No loader service found");
    }

    @Override
    public String getModVersion(Path file) {
        getServiceLoader().getModVersion(file);

        throw new AssertionError("No loader service found");
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        getServiceLoader().isDevelopmentEnvironment();

        throw new AssertionError("No loader service found");
    }

    @Override
    public String getModEnvironment(String modId) {
        getServiceLoader().getModEnvironment(modId);

        throw new AssertionError("No loader service found");
    }

    @Override
    public String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
        getServiceLoader().getModIdFromLoadedJar(file, checkAlsoOutOfContainer);

        throw new AssertionError("No loader service found");
    }

    @Override
    public String getModIdFromNotLoadedJar(Path file) {
        getServiceLoader().getModIdFromNotLoadedJar(file);

        throw new AssertionError("No loader service found");
    }
}
