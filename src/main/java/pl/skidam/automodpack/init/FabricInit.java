package pl.skidam.automodpack.init;

//#if FABRIC
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.modpack.Commands;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

import static pl.skidam.automodpack_core.GlobalVariables.*;

//#if MC < 1192
//$$ import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
//#else
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//#endif

public class FabricInit {

    public static void onInitialize() {

        preload = false;
        ScreenManager.INSTANCE = new ScreenImpl();

        long start = System.currentTimeMillis();
        LOGGER.info("Launching AutoModpack...");

        Common.init();

        if (LOADER_MANAGER.getEnvironmentType() == LoaderService.EnvironmentType.SERVER) {
            Common.serverInit();
            if (serverConfig.generateModpackOnStart) {
                LOGGER.info("Generating modpack...");
                long genStart = System.currentTimeMillis();
                if (modpack.generateNew()) {
                    LOGGER.info("Modpack generated! took " + (System.currentTimeMillis() - genStart) + "ms");
                } else {
                    LOGGER.error("Failed to generate modpack!");
                }
            }
            ModPackets.registerS2CPackets();
        } else {
            ModPackets.registerC2SPackets();
            new AudioManager();
        }

//#if MC >= 1192
    CommandRegistrationCallback.EVENT.register((dispatcher, w, dedicated) -> {
//#else
//$$   CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
//#endif
       Commands.register(dispatcher);
   });

        LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
    }
}
//#endif