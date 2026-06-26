package pl.skidam.automodpack.init;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import pl.skidam.automodpack.loader.GameCall;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

import java.util.HashMap;
import java.util.Map;

import static pl.skidam.automodpack_core.Constants.*;

public class Common {

    public static Map<String, Boolean> players = new HashMap<>();
    public static MinecraftServer server = null;

    public static void serverInit() {
        if (serverConfig.generateModpackOnStart) {
            LOGGER.info("Generating modpack...");
            long genStart = System.currentTimeMillis();
            if (modpackExecutor.generateNew()) {
                LOGGER.info("Modpack generated! took " + (System.currentTimeMillis() - genStart) + "ms");
            } else {
                LOGGER.error("Failed to generate modpack!");
            }
        } else {
            LOGGER.info("Loading last modpack...");
            long genStart = System.currentTimeMillis();
            if (modpackExecutor.loadLast()) {
                LOGGER.info("Modpack loaded! took " + (System.currentTimeMillis() - genStart) + "ms");
            } else {
                LOGGER.error("Failed to load modpack!");
            }
        }

        ModPackets.registerS2CPackets();
    }

    public static void init() {
        GAME_CALL = new GameCall();
        hostServer = new NettyServer();
        modpackExecutor = new ModpackExecutor();
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
        modpackExecutor.stop();
    }

    public static Identifier id(String path) {
        /*? if >=1.21.11 {*/
        return Identifier.tryBuild(MOD_ID, path);
        /*?} else if >=1.19.2 {*/
        /*return Identifier.tryBuild(MOD_ID, path);
        *//*?} else {*/
        /*return new Identifier(MOD_ID, path);
        *//*?}*/
    }
}
