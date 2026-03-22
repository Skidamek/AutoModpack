package pl.skidam.automodpack.init;

/*? if forge {*/
/*import pl.skidam.automodpack.modpack.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.common.Mod;

import static pl.skidam.automodpack_core.Constants.*;

@Mod(MOD_ID + "_mod")
public class ForgeInit {

    public ForgeInit() {
        preload = false;

        long start = System.currentTimeMillis();
        LOGGER.info("Launching AutoModpack...");

        Common.init();

        if (FMLLoader.getDist() == Dist.DEDICATED_SERVER) {
            Common.serverInit();
        } else {
            ForgeClientInit.init(FMLJavaModLoadingContext.get().getModEventBus());
        }


        LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
    }

    @Mod.EventBusSubscriber(modid = MOD_ID + "_mod")
    public static class events {
        @SubscribeEvent
        public static void onCommandsRegister(RegisterCommandsEvent event) {
            Commands.register(event.getDispatcher());
        }
    }
}
*//*?}*/
