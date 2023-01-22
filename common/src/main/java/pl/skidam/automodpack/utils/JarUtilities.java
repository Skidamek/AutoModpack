package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.Platform;

import java.io.File;
import java.nio.file.Path;

public class JarUtilities {

    // Most of the things here are got from Platform class but to have better code readability I decided to return them here

    /**
     * @param modId unique mod id
     * @return jar file name of mod with given modId
     */

    public static Path getModJarPath(String modId) {
        File jarDir = Platform.getModPath(modId);

        if (jarDir == null) {
            AutoModpack.LOGGER.error("Could not find jar file for " + modId);
            return null;
        }

        return jarDir.toPath().toAbsolutePath();
    }

    public static String getJarFileOfMod(String modId) {
        File jarDir = Platform.getModPath(modId);

        if (jarDir == null) {
            AutoModpack.LOGGER.error("Could not find jar file for " + modId);
            return null;
        }

        return jarDir.getName(); // returns name of the file
    }

    /**
     * @param jarFile path to jar file (mod)
     * @return unique id of mod
     */
    public static String getModIdFromJar(File jarFile, boolean checkAlsoOutOfContainer) {
        return Platform.getModIdFromLoadedJar(jarFile, checkAlsoOutOfContainer);
    }

    /**
     * @param modId unique id of some mod
     * @return mod version of mod with given modId
     */
    public static String getModVersion(String modId) {
        return Platform.getModVersion(modId);
    }

    /**
     * @param file path to jar file (mod)
     * @return mod version of mod from given jar file
     */
    public static String getModVersion(File file) {
        return Platform.getModVersion(file);
    }

    /**
     * @return Returns mods directory (exactly the directory where `automodpack` mod is loaded)
     */

    public static File getModsDirectory() {
        File modsPath = new File("./mods/");
        if (!Platform.isDevelopmentEnvironment()) {
            File jarDir = Platform.getModPath("automodpack"); // we will use automodpack because it is always present

            modsPath = jarDir.getParentFile(); // get parent directory, which should be a mods directory

            if (!modsPath.getName().equals("mods")) {
                AutoModpack.LOGGER.warn("Found external mods folder ({})", modsPath);
            }
        }
        return modsPath;
    }
}