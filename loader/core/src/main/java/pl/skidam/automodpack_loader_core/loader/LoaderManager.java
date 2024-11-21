package pl.skidam.automodpack_loader_core.loader;

import pl.skidam.automodpack_core.loader.LoaderManagerService;

import java.nio.file.Path;
import java.util.Collection;

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
    public Collection<Mod> getModList() {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public Mod getMod(String modId) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public Mod getMod(Path file) {
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
    public String getModIdFromNotLoadedJar(Path file) {
        throw new AssertionError("Loader class not found");
    }
}
