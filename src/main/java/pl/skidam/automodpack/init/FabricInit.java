package pl.skidam.automodpack.init;

/*? if fabric {*/
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.modpack.Commands;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import net.fabricmc.fabric.api.command./*? if <1.19.1 {*/ /*v1 *//*?} else {*/ v2 /*?}*/.CommandRegistrationCallback;

public class FabricInit {

    public static void onInitialize() {

        preload = false;
        ScreenManager.INSTANCE = new ScreenImpl();

        long start = System.currentTimeMillis();
        LOGGER.info("Launching AutoModpack...");

        Common.init();

        if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER) {
            Common.serverInit();
        } else {
            ModPackets.registerC2SPackets();
            new AudioManager();
        }

    CommandRegistrationCallback.EVENT.register((dispatcher, /*? if >=1.19.1 {*/ w, /*?}*/ dedicated) -> {
       Commands.register(dispatcher);
   });

        LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
    }
}
/*?}*/