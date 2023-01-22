package pl.skidam.automodpack.forge;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import pl.skidam.automodpack.Platform;

import java.io.File;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

import static pl.skidam.automodpack.Platform.ModPlatform.FORGE;
import static pl.skidam.automodpack.utils.JarUtilities.getJarFileOfMod;

public class PlatformImpl {
    public static Platform.ModPlatform getPlatformType() {
        return FORGE;
    }

    public static boolean isModLoaded(String modid) {
        return ModList.get().isLoaded(modid);
    }

    public static Collection getModList() {
        Collection<IModInfo> modList = ModList.get().getMods();

        modList = modList.stream().filter(mod -> !mod.getModId().equalsIgnoreCase("forge") && !Objects.requireNonNull(getJarFileOfMod(mod.getModId().split(" ")[0])).equalsIgnoreCase("forge")).collect(Collectors.toList());
        modList = modList.stream().filter(mod -> !mod.getModId().equalsIgnoreCase("fabric-") && !Objects.requireNonNull(getJarFileOfMod(mod.toString().split(" ")[0])).toLowerCase().startsWith("fabric-")).collect(Collectors.toList());
        modList = modList.stream().filter(mod -> !mod.getModId().equalsIgnoreCase("quilt_") && !Objects.requireNonNull(getJarFileOfMod(mod.toString().split(" ")[0])).toLowerCase().startsWith("quilt_")).collect(Collectors.toList());
        modList = modList.stream().filter(mod -> !mod.getModId().equalsIgnoreCase("quilted_") && !Objects.requireNonNull(getJarFileOfMod(mod.toString().split(" ")[0])).toLowerCase().startsWith("quilted_")).collect(Collectors.toList());
        modList = modList.stream().filter(mod -> !mod.getModId().equalsIgnoreCase("minecraft")).collect(Collectors.toList());
        modList = modList.stream().filter(mod -> !mod.getModId().equalsIgnoreCase("java")).collect(Collectors.toList());

        return modList;
    }

    public static void downloadDependencies() {
        // We don't need to download anything on forge
    }

    public static File getModPath(String modid) {
        return Platform.isModLoaded(modid) ?
                ModList.get().getModFileById(modid).getFile().getFilePath().toFile() :
                null;
    }

    public static String getEnvironmentType() {
        if (FMLEnvironment.dist.isClient()) {
            return "CLIENT";
        } else if (FMLEnvironment.dist.isDedicatedServer()) {
            return "SERVER";
        } else {
            return "UNKNOWN";
        }
    }

    public static String getModVersion(String modid) {
        return ModList.get().getModContainerById(modid).isPresent() ? ModList.get().getModContainerById(modid).get().getModInfo().getVersion().toString() : null;
    }

    public static String getConfigDir() {
        return FMLPaths.CONFIGDIR.get().toString();
    }

    public static boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }

    public static String getModEnvironment(String modid) {
        // TODO: Implement this
//        return ModList.get().getModContainerById(modid).isPresent() ? ModList.get().getModContainerById(modid).get().getModInfo().getEnvironment().toString() : null; // idk how to do that lmao
        return null;
    }

    public static String getModVersion(File file) {
        return null;
    }

    public static String getModIdFromNotLoadedJar(File file) {
        return null;
    }

    public static String getModIdFromLoadedJar(File file, boolean checkAlsoOutOfContainer) {
        for (IModFileInfo mod : ModList.get().getModFiles()) {
            if (mod.getFile().getFileName().equals(file.getName())) {
                return mod.getFile().getModInfos().get(0).getModId();
            }
        }
        return null;
    }

    public static String getModEnvironmentFromNotLoadedJar(File file) {
        return file.toString();
    }
}
