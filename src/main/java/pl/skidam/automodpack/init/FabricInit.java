package pl.skidam.automodpack.init;

/*? if fabric {*/
import pl.skidam.automodpack.modpack.Commands;
import net.fabricmc.fabric.api.command./*? if <1.19.1 {*/ /*v1 *//*?} else {*/ v2 /*?}*/.CommandRegistrationCallback;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import static pl.skidam.automodpack_core.Constants.*;

public class FabricInit {

    public static void onInitialize() {

        preload = false;

        long start = System.currentTimeMillis();
        LOGGER.info("Launching AutoModpack...");

        Common.init();

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            Common.serverInit();
        }

    CommandRegistrationCallback.EVENT.register((dispatcher, /*? if >=1.19.1 {*/ w, /*?}*/ dedicated) -> {
       Commands.register(dispatcher);
   });

        LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
    }
}
/*?}*/
