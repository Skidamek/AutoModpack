package pl.skidam.automodpack_loader_core.loader;

import java.nio.file.Path;
import java.util.Collection;

public interface LoaderService {

    enum ModPlatform { FABRIC, QUILT, FORGE, NEOFORGE }
    enum EnvironmentType { CLIENT, SERVER, UNIVERSAL }
    record Mod(String modID, String modVersion, Path modPath, boolean JiJ, EnvironmentType environmentType) {}

    ModPlatform getPlatformType();

    boolean isModLoaded(String modId);

    Collection<Mod> getModList();

    String getLoaderVersion();

    Path getModPath(String modId);

    String getModVersion(String modId);

    String getModVersion(Path file);
    boolean isDevelopmentEnvironment();
    EnvironmentType getEnvironmentType();
    EnvironmentType getModEnvironment(String modId);
    EnvironmentType getModEnvironmentFromNotLoadedJar(Path file);

    String getModId(Path file, boolean checkAlsoOutOfContainer);
    String getModIdFromNotLoadedJar(Path file);
    boolean isJiJedMod(String modId);
}