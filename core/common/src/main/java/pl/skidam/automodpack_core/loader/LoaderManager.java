package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.Collection;

public class LoaderManager implements LoaderService {
    @Override
    public ModPlatform getPlatformType() {
        throw new AssertionError("No loader class found");
    }

    @Override
    public boolean isModLoaded(String modId) {
        throw new AssertionError("No loader class found");
    }

    @Override
    public Collection getModList() {
        throw new AssertionError("No loader class found");
    }

    @Override
    public String getLoaderVersion() {
        throw new AssertionError("No loader class found");
    }

    @Override
    public Path getModPath(String modId) {
        throw new AssertionError("No loader class found");
    }

    @Override
    public String getEnvironmentType() {
        throw new AssertionError("No loader class found");
    }

    @Override
    public String getModEnvironmentFromNotLoadedJar(Path file) {
        throw new AssertionError("No loader class found");
    }

    @Override
    public String getModVersion(String modId) {
        throw new AssertionError("No loader class found");
    }

    @Override
    public String getModVersion(Path file) {
        throw new AssertionError("No loader class found");
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        throw new AssertionError("No loader class found");
    }

    @Override
    public String getModEnvironment(String modId) {
        throw new AssertionError("No loader class found");
    }

    @Override
    public String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
        throw new AssertionError("No loader class found");
    }

    @Override
    public String getModIdFromNotLoadedJar(Path file) {
        throw new AssertionError("No loader class found");
    }
}
