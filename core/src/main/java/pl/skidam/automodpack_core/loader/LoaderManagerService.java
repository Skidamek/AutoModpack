package pl.skidam.automodpack_core.loader;

import pl.skidam.automodpack_core.utils.FileInspection;

import java.util.Collection;

public interface LoaderManagerService {

    enum ModPlatform { FABRIC, QUILT, FORGE, NEOFORGE }
    enum EnvironmentType { CLIENT, SERVER, UNIVERSAL }

    ModPlatform getPlatformType();
    Collection<FileInspection.Mod> getModList();
    boolean isModLoaded(String modId);
    String getLoaderVersion();
    EnvironmentType getEnvironmentType();
    boolean isDevelopmentEnvironment();
    String getModVersion(String modId);
}