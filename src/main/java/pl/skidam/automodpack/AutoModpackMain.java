package pl.skidam.automodpack;

import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import me.shedaniel.autoconfig.AutoConfig;
import pl.skidam.automodpack.config.AutoModpackConfig;
import pl.skidam.automodpack.server.Commands;

import java.io.File;

public class AutoModpackMain implements ModInitializer {

    // TODO make cloth-config unnecessary

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");
    public static final String MOD_ID = "automodpack";
    public static final Identifier AM_CHECK = new Identifier(MOD_ID, "check");
    public static final Identifier AM_LINK = new Identifier(MOD_ID, "link");
    public static final Identifier AM_KICK = new Identifier(MOD_ID, "kick");
    public static String AutoModpackUpdated;
    public static String ModpackUpdated;
    public static boolean Checking;
    public static String link;
    public static final File out = new File("./AutoModpack/modpack.zip");
    public static final String selfLink = "https://github.com/Skidamek/AutoModpack/releases/latest/download/AutoModpack-1.19.x.jar";
    public static final File selfOut = new File( "./mods/AutoModpack.jar");
    public static final String trashLink = "https://github.com/Skidamek/TrashMod/releases/download/latest/trash.jar";
    public static final File trashOut = new File("./AutoModpack/TrashMod.jar");

    public static String VERSION = FabricLoader.getInstance().getModContainer("automodpack").get().getMetadata().getVersion().getFriendlyString();

    @Override
    public void onInitialize() {

        // Initialize AutoConfig
        AutoConfig.register(AutoModpackConfig.class, JanksonConfigSerializer::new);

        Commands.register();
    }
}
