package pl.skidam.automodpack.utils;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.apache.commons.lang3.StringUtils;
import pl.skidam.automodpack.AutoModpackMain;

import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;

public class JarUtilities {

    public static String correctName;
    public static File selfOut;
    public static File selfBackup;

    public static String getJarFileOfMod(String modId) {
        String jarFile = null;

        // Get jar file
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ModContainer container = FabricLoader.getInstance().getModContainer(modId)
                    .orElseThrow(() -> new IllegalStateException("Could not find jar file for " + modId));

            Path jarPath = container.getRootPaths().stream().findFirst().isPresent() ? container.getRootPaths().stream().findFirst().get() : null;

            if (jarPath == null) {
                AutoModpackMain.LOGGER.error("Could not find jar file for " + modId);
                return null;
            }

            FileSystem fileSystem = jarPath.getFileSystem();

            jarFile = fileSystem.toString().replace("\\", File.separator);
            jarFile = StringUtils.substringAfterLast(jarFile, File.separator);

            if (modId.equals("automodpack")) {
                correctName = jarFile;
                selfOut = new File(fileSystem.toString());
                selfBackup = new File("./AutoModpack/" + correctName);
            }
        }

        return jarFile; // returns name of the file
    }

    public static Path getModsPath() {
        Path modsPath = selfOut.getParentFile().toPath();
        if (!modsPath.getFileName().toString().equals("mods")) {
            AutoModpackMain.LOGGER.info("Found external mods folder ({})", modsPath.getFileName());
        }
        return modsPath;
    }

    public static Collection<ModContainer> getListOfModsIDS() {
        Collection<ModContainer> modsList = FabricLoader.getInstance().getAllMods();

        // make for each loop to modsList
        for (ModContainer mod : modsList) {
            String jarFile = getJarFileOfMod(mod.toString().split(" ")[0]);
            assert jarFile != null;
            if (jarFile.startsWith("fabric-") && mod.toString().startsWith("fabric-")) {
                modsList = (Collection<ModContainer>) modsList.toArray()[modsList.toArray().length - 1];
            }
        }

        AutoModpackMain.LOGGER.warn("Found {} mods:\n{}", modsList.size() ,modsList);

        return modsList;
    }
}