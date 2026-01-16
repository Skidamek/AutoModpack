package pl.skidam.automodpack_core.loader;

public interface LoaderManagerService {
    enum ModPlatform { FABRIC, QUILT, FORGE, NEOFORGE }
    enum EnvironmentType { CLIENT, SERVER, UNIVERSAL }

    ModPlatform getPlatformType();
    String getLoaderVersion();
    EnvironmentType getEnvironmentType();
    boolean isDevelopmentEnvironment();
    String getModVersion(String modId);
}