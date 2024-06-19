package pl.skidam.automodpack_loader_core.loader;

import pl.skidam.automodpack_core.loader.LoaderService;

import java.nio.file.Path;
import java.util.Collection;

public class LoaderManager implements LoaderService {
    @Override
    public ModPlatform getPlatformType() {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public boolean isModLoaded(String modId) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public Collection<Mod> getModList() {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public String getLoaderVersion() {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public Path getModPath(String modId) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public EnvironmentType getEnvironmentType() {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public EnvironmentType getModEnvironmentFromNotLoadedJar(Path file) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public String getModVersion(String modId) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public String getModVersion(Path file) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public EnvironmentType getModEnvironment(String modId) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public String getModId(Path file, boolean checkAlsoOutOfContainer) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public String getModIdFromNotLoadedJar(Path file) {
        throw new AssertionError("Loader class not found");
    }
}
