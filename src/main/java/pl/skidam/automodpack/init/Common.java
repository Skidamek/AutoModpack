package pl.skidam.automodpack.init;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import pl.skidam.automodpack.loader.GameCall;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.modpack.FullServerPack;
import pl.skidam.automodpack_core.modpack.Modpack;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

import java.util.HashMap;
import java.util.Map;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Common {

    public static Map<String, Boolean> players = new HashMap<>();
    public static MinecraftServer server = null;

    public static void serverInit() {
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
        if (serverConfig.enableFullServerPack) {
            LOGGER.info("Generating FullServerModpack...");
            long genStart = System.currentTimeMillis();
            if (fullpacks.generateNew()) {
                LOGGER.info("FullServerModpack generated! took " + (System.currentTimeMillis() - genStart) + "ms");
            } else {
                LOGGER.error("Failed to generate fullservermodpack!");
            }
        }

        ModPackets.registerS2CPackets();
    }

    public static void init() {
        GAME_CALL = new GameCall();
        hostServer = new NettyServer();
        modpack = new Modpack();
        fullpacks = new FullServerPack();
    }

    public static void afterSetupServer() {
        if (LOADER_MANAGER.getEnvironmentType() != LoaderManagerService.EnvironmentType.SERVER) {
            return;
        }

        hostServer.start();
    }

    public static void beforeShutdownServer() {
        if (LOADER_MANAGER.getEnvironmentType() != LoaderManagerService.EnvironmentType.SERVER) {
            return;
        }

        hostServer.stop();
        modpack.shutdownExecutor();
        fullpacks.shutdownExecutor();
    }

    public static Identifier id(String path) {
        /*? if >1.19.1 {*/
        return Identifier.of(MOD_ID, path);
        /*?} else {*/
        /*return new Identifier(MOD_ID, path);
        *//*?}*/
    }
}
