package pl.skidam.automodpack_loader_core.utils;

import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;

import java.io.*;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import net.fabricmc.loader.api.FabricLoader;

import static pl.skidam.automodpack_core.GlobalVariables.AM_VERSION;
import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class SelectionManager {

    //select folders from host-modpack for difference client packs
    public static List<String> getModpackFolders() {
        List<String> modpacks = new ArrayList<>();

        File modpackfolders = new File(FabricLoader.getInstance().getGameDir().toFile(), "automodpack/host-modpack/");
        File[] folders = modpackfolders.listFiles(File::isDirectory);

        if (folders != null) {
            for (File dir : folders) {
                if (!dir.getName().equalsIgnoreCase("main")) { // "main" überspringen
                    modpacks.add(dir.getName());
                }
            }
        }
        return modpacks;
    }

    //give only main Pack
    private static String selectedPack ="main";

    //give modpack from selected modpack and save to config
    public static void setSelectedPack(String modpack) {
        selectedPack = modpack;
            //TO DO Adding in Config... but how xD
    }

    //fetch modpack from config
    public static String getSelectedPack() {
        return selectedPack;
    }

}