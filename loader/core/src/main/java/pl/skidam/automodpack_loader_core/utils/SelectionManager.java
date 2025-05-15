package pl.skidam.automodpack_loader_core.utils;

import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import java.io.*;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.clientConfig;
import static pl.skidam.automodpack_core.GlobalVariables.clientConfigFile;

public class SelectionManager {

    private static String selectedPack = "main";

    //select folders from host-modpack for difference client packs
    public static List<String> getModpackFolders() {
        List<String> modpacks = new ArrayList<>();

        File modpackfolders = ModpackUtils.getClientPackage().toFile();
        File[] folders = modpackfolders.listFiles(File::isDirectory);

        if (folders != null) {
            for (File dir : folders) {
                if (!dir.getName().equalsIgnoreCase("fullserver")) { // not using fullserver
                    modpacks.add(dir.getName());
                }
            }
        }
        return modpacks;
    }

    //give modpack from selected modpack and save to config
    public static void setSelectedPack(String modpack) {
        if (Objects.equals(selectedPack, modpack)) return;
        selectedPack = modpack;
        //trying to add selected Modpack
        if (!modpack.equalsIgnoreCase("fullserver")) {
            clientConfig.selectedModpack = modpack;
            ConfigTools.save(clientConfigFile, clientConfig);
        }
    }

    //fetch modpack from config
    public static String getSelectedPack() {
        return selectedPack;
    }
}