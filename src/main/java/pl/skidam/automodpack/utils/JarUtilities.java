package pl.skidam.automodpack.utils;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.apache.commons.lang3.StringUtils;
import org.quiltmc.loader.api.QuiltLoader;
import pl.skidam.automodpack.AutoModpackMain;

import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

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

    public static Collection getListOfModsIDS() {
        Collection modList;
        if (FabricLoader.getInstance().isModLoaded("quilt_loader")) {
            modList = new quiltModList().quiltModList(); // Quilt doest support fabric method for getting mod list, so
        } else {
            modList = new fabricModList().fabricModList();
        }
        return modList;
    }

    private static class quiltModList {
        private Collection quiltModList() {
            Collection<org.quiltmc.loader.api.ModContainer> modsList = QuiltLoader.getAllMods();
            // Remove every mod that is (fabric or quilt api something) from the list
            modsList = modsList.stream().filter(m -> !m.toString().startsWith("fabric-") && !Objects.requireNonNull(getJarFileOfMod(m.toString().split(" ")[0])).startsWith("fabric-")).collect(Collectors.toList());
            modsList = modsList.stream().filter(m -> !m.toString().startsWith("quilt_") && !Objects.requireNonNull(getJarFileOfMod(m.toString().split(" ")[0])).startsWith("quilt_")).collect(Collectors.toList());
            modsList = modsList.stream().filter(m -> !m.toString().startsWith("quilted_") && !Objects.requireNonNull(getJarFileOfMod(m.toString().split(" ")[0])).startsWith("quilted_")).collect(Collectors.toList());

            modsList = modsList.stream().filter(m -> !m.toString().startsWith("minecraft") && !Objects.requireNonNull(getJarFileOfMod(m.toString().split(" ")[0])).startsWith("minecraft")).collect(Collectors.toList());

            // Remove java from the list
            modsList = modsList.stream().filter(m -> !m.toString().startsWith("java")).collect(Collectors.toList());
            return modsList;
        }
    }
    private static class fabricModList {
        private Collection fabricModList() {
            Collection<ModContainer> modsList = FabricLoader.getInstance().getAllMods();
            // Remove every mod that is (fabric or quilt api something) from the list
            modsList = modsList.stream().filter(m -> !m.toString().startsWith("fabric-") && !Objects.requireNonNull(getJarFileOfMod(m.toString().split(" ")[0])).startsWith("fabric-")).collect(Collectors.toList());
            modsList = modsList.stream().filter(m -> !m.toString().startsWith("quilt_") && !Objects.requireNonNull(getJarFileOfMod(m.toString().split(" ")[0])).startsWith("quilt_")).collect(Collectors.toList());
            modsList = modsList.stream().filter(m -> !m.toString().startsWith("quilted_") && !Objects.requireNonNull(getJarFileOfMod(m.toString().split(" ")[0])).startsWith("quilted_")).collect(Collectors.toList());

            modsList = modsList.stream().filter(m -> !m.toString().startsWith("minecraft") && !Objects.requireNonNull(getJarFileOfMod(m.toString().split(" ")[0])).startsWith("minecraft")).collect(Collectors.toList());

            // Remove java from the list
            modsList = modsList.stream().filter(m -> !m.toString().startsWith("java")).collect(Collectors.toList());
            return modsList;
        }
    }
}