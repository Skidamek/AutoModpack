package pl.skidam.automodpack;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class AutoModpackMain implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");
    public static String AutoModpackUpdated;
    public static String ModpackUpdated;
    public static boolean Checking;
    public static final String link = "http://130.61.177.253/download/modpack.zip";
    public static final File out = new File("./AutoModpack/modpack.zip");
    public static final String selfLink = "https://github.com/Skidamek/AutoModpack/releases/download/pipel/AutoModpack.jar";
    public static final File selfOut = new File( "./mods/AutoModpack.jar");
    public static final String trashLink = "https://github.com/Skidamek/TrashMod/releases/download/latest/trash.jar";
    public static final File trashOut = new File("./AutoModpack/TrashMod.jar");

    @Override
    public void onInitialize() {

    }
}
