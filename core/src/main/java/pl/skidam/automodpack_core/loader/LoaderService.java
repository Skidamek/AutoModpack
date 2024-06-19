package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.Collection;

public interface LoaderService {

    enum ModPlatform { FABRIC, QUILT, FORGE, NEOFORGE }
    enum EnvironmentType { CLIENT, SERVER, UNIVERSAL }
    record Mod(String modID, String modVersion, Path modPath, EnvironmentType environmentType, Collection<String> dependencies) {}
    ModPlatform getPlatformType();
    Collection<Mod> getModList();
    Mod getMod(String modId);
    Mod getMod(Path file);
    boolean isModLoaded(String modId);
    String getLoaderVersion();
    EnvironmentType getEnvironmentType();
    boolean isDevelopmentEnvironment();


    // TODO merge all of these methods into 2 methods one getting modId and one getting file path, both returning optional of Mod object
    Path getModPath(String modId);
    String getModVersion(String modId);
    String getModVersion(Path file);
    EnvironmentType getModEnvironment(String modId);
    EnvironmentType getModEnvironmentFromNotLoadedJar(Path file);
    String getModIdFromNotLoadedJar(Path file);
}