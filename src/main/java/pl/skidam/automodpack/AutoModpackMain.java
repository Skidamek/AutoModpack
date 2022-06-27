package pl.skidam.automodpack;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.skidam.automodpack.server.Commands;

import java.io.File;

public class AutoModpackMain implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");
    public static final String MOD_ID = "automodpack";
    public static String ENV_BRAND = "null";
    public static final Identifier AM_CHECK = new Identifier(MOD_ID, "check");
    public static final Identifier AM_LINK = new Identifier(MOD_ID, "link");
    public static String AutoModpackUpdated;
    public static String ModpackUpdated;
    public static String link;
    public static final File out = new File("./AutoModpack/modpack.zip");
    public static final String selfLink = "https://github.com/Skidamek/AutoModpack/releases/latest/download/AutoModpack-1.19.x.jar";
    public static final File selfOut = new File( "./mods/AutoModpack-1.19.x.jar");
    public static final String trashLink = "https://github.com/Skidamek/TrashMod/releases/download/latest/trash.jar";
    public static final File trashOut = new File("./AutoModpack/TrashMod.jar");
    public static boolean isClothConfig = false;
    public static boolean isModMenu = false;
    public static String VERSION = FabricLoader.getInstance().getModContainer("automodpack").get().getMetadata().getVersion().getFriendlyString();

    @Override
    public void onInitialize() {

        Commands.register();
    }
}
