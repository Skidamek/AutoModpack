package pl.skidam.automodpack;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class AutoModpackMain implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");
    public static final String MOD_ID = "automodpack";

    public static final Identifier AM_CHECK = new Identifier(MOD_ID, "check");
    public static final Identifier AM_LINK = new Identifier(MOD_ID, "link");

    // Client
    public static String AutoModpackUpdated;
    public static String ModpackUpdated;
    public static boolean Checking;
    public static String link;
    public static final File out = new File("./AutoModpack/modpack.zip");
    public static final String selfLink = "https://github.com/Skidamek/AutoModpack/releases/latest/download/AutoModpack-1.19.x.jar";
    public static final File selfOut = new File( "./mods/AutoModpack.jar");
    public static final String trashLink = "https://github.com/Skidamek/TrashMod/releases/download/latest/trash.jar";
    public static final File trashOut = new File("./AutoModpack/TrashMod.jar");


    // Server

    public static int host_port = 30037;
    public static int host_thread_count = 2;
    public static String host_external_ip = "";
    public static int time_out = 1250; // in milliseconds


    @Override
    public void onInitialize() {


    }

}
