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
    public static final Identifier AM_LINK = new Identifier(MOD_ID, "link");
    public static String AutoModpackUpdated;
    public static String ModpackUpdated;
    public static String link;
    public static final File out = new File("./AutoModpack/modpacks/modpack.zip"); // TODO name modpack of the server ip
    public static String correctName = "AutoModpack-1.18.x.jar";
    public static File selfOut = new File( "./mods/" + correctName);
    public static final File selfBackup = new File("./AutoModpack/" + correctName);
    public static boolean isClothConfig = false;
    public static boolean isModMenu = false;
    public static boolean isVelocity = false;
    public static String VERSION = FabricLoader.getInstance().getModContainer("automodpack").isPresent() ? FabricLoader.getInstance().getModContainer("automodpack").get().getMetadata().getVersion().getFriendlyString() : null;
    public static boolean isFabricLoader = false;
    public static boolean isQuiltLoader = false;
    @Override
    public void onInitialize() {

        Commands.register();
    }
}