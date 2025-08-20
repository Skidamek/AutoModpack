package pl.skidam.automodpack.init;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import pl.skidam.automodpack.loader.GameCall;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.modpack.FullServerPack;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

import java.util.HashMap;
import java.util.Map;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Common {

    public static Map<String, Boolean> players = new HashMap<>();
    public static MinecraftServer server = null;

    public static void serverInit() {
        for (String groupId : serverConfig.groups.keySet()) {
            var groupDecl = serverConfig.groups.get(groupId);
            if (groupDecl.generateModpackOnStart) {
                LOGGER.info("Generating modpack...");
                long genStart = System.currentTimeMillis();
                if (modpackExecutor.generateNew(groupId)) {
                    LOGGER.info("Modpack generated! took " + (System.currentTimeMillis() - genStart) + "ms");
                } else {
                    LOGGER.error("Failed to generate modpack!");
                }
            } else {
                LOGGER.info("Loading last modpack...");
                long genStart = System.currentTimeMillis();
                if (modpackExecutor.loadLast(groupId)) {
                    LOGGER.info("Modpack loaded! took " + (System.currentTimeMillis() - genStart) + "ms");
                } else {
                    LOGGER.error("Failed to load modpack!");
                }
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

        modpackExecutor = new ModpackExecutor();
        fullpacks = new FullServerPack(modpackExecutor);

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
        fullpacks.shutdownExecutor();
    }

    public static ResourceLocation id(String path) {
        /*? if >1.19.1 {*/
        return ResourceLocation.tryBuild(MOD_ID, path);
        /*?} else {*/
        /*return new ResourceLocation(MOD_ID, path);
        *//*?}*/
    }
}
