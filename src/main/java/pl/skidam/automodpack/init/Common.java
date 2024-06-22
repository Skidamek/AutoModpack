package pl.skidam.automodpack.init;

import net.minecraft.util.Identifier;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.modpack.Modpack;
import pl.skidam.automodpack_core.netty.HttpServer;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_core.loader.LoaderService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Common {

    // True if has AutoModpack installed
    public static Map<String, Boolean> players = new HashMap<>();

    public static void serverInit() {
        modpack = new Modpack();

        if (serverConfig.generateModpackOnStart) {
            LOGGER.info("Generating modpack...");
            long genStart = System.currentTimeMillis();
            if (modpack.generateNew()) {
                LOGGER.info("Modpack generated! took " + (System.currentTimeMillis() - genStart) + "ms");
            } else {
                LOGGER.error("Failed to generate modpack!");
            }
        } else {
            LOGGER.info("Loading last modpack...");
            long genStart = System.currentTimeMillis();
            if (modpack.loadLast()) {
                LOGGER.info("Modpack loaded! took " + (System.currentTimeMillis() - genStart) + "ms");
            } else {
                LOGGER.error("Failed to load modpack!");
            }
        }

        ModPackets.registerS2CPackets();
    }

    public static void init() {
        httpServer = new HttpServer();
    }

    public static void afterSetupServer() {
        if (LOADER_MANAGER.getEnvironmentType() != LoaderService.EnvironmentType.SERVER) {
            return;
        }

        try {
            httpServer.start();
        } catch (IOException e) {
            LOGGER.error("Couldn't start server.", e);
        }
    }

    public static void beforeShutdownServer() {
        if (new LoaderManager().getEnvironmentType() != LoaderService.EnvironmentType.SERVER) {
            return;
        }

        httpServer.stop();
        modpack.shutdownExecutor();
    }

    public static Identifier id(String path) {
        //#if MC >= 1210
        return Identifier.of(MOD_ID, path);
        //#else
//$$         return new Identifier(MOD_ID, path);
        //#endif
    }
}
