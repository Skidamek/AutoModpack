package pl.skidam.automodpack_core.forge;

import pl.skidam.automodpack_core.LoaderService;

import java.nio.file.Path;
import java.util.Collection;

public class ForgeLoaderImpl implements LoaderService {

    @Override
    public ModPlatform getPlatformType() {
        return null;
    }

    @Override
    public boolean isModLoaded(String modId) {
        return false;
    }

    @Override
    public Collection getModList() {
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
    public String getEnvironmentType() {
        return null;
    }

    @Override
    public String getModEnvironmentFromNotLoadedJar(Path file) {
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
    public String getModEnvironment(String modId) {
        return null;
    }

    @Override
    public String getModIdFromLoadedJar(Path file, boolean checkAlsoOutOfContainer) {
        return null;
    }

    @Override
    public String getModIdFromNotLoadedJar(Path file) {
        return null;
    }
}
