package pl.skidam.automodpack.init;

/*? if forge {*/
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.modpack.Commands;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;

import static pl.skidam.automodpack_core.GlobalVariables.*;

@Mod(MOD_ID)
public class ForgeInit {

    public ForgeInit() {
        preload = false;
        ScreenManager.INSTANCE = new ScreenImpl();

        long start = System.currentTimeMillis();
        LOGGER.info("Launching AutoModpack...");

         Common.init();

        if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER) {
            Common.serverInit();
        } else {
            ModPackets.registerC2SPackets();
            new AudioManager(FMLJavaModLoadingContext.get().getModEventBus());
        }


        LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
    }

    @Mod.EventBusSubscriber(modid = MOD_ID)
    public static class events {
        @SubscribeEvent
        public static void onCommandsRegister(RegisterCommandsEvent event) {
            Commands.register(event.getDispatcher());
        }
    }
}
/*?}*/