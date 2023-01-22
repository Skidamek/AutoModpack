package pl.skidam.automodpack;

import dev.architectury.injectables.annotations.ExpectPlatform;

import java.io.File;
import java.util.Collection;

public class Platform {
    public static final boolean Fabric;
    public static final boolean Quilt;
    public static final boolean Forge;
    static
    {
        Fabric = getPlatformType() == ModPlatform.FABRIC;
        Quilt = getPlatformType() == ModPlatform.QUILT;
        Forge  = getPlatformType() == ModPlatform.FORGE;
    }

    public enum ModPlatform { FABRIC, QUILT, FORGE }
    @ExpectPlatform
    public static boolean isDevelopmentEnvironment() {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static ModPlatform getPlatformType() {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static boolean isModLoaded(String modid) {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static Collection getModList() {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static File getModPath(String modid) {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static String getModEnvironment(String modid) {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static String getModEnvironmentFromNotLoadedJar(File file) {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static String getModIdFromLoadedJar(File file, boolean checkAlsoOutOfContainer) {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static String getModIdFromNotLoadedJar(File file) {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static String getModVersion(String modid) {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static String getModVersion(File file) {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static void downloadDependencies() {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static String getEnvironmentType() {
        throw new AssertionError();
    }
    @ExpectPlatform
    public static String getConfigDir() {
        throw new AssertionError();
    }
}