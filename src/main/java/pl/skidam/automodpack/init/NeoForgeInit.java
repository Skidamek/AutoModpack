package pl.skidam.automodpack.init;

/*? if neoforge {*/
/*/^? if >1.20.5 {^/
/^import net.neoforged.fml.common.EventBusSubscriber;
^//^?}^/
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.modpack.Commands;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static pl.skidam.automodpack_core.GlobalVariables.*;

@Mod(MOD_ID)
public class NeoForgeInit {
    public NeoForgeInit(IEventBus eventBus) {
         preload = false;
         ScreenManager.INSTANCE = new ScreenImpl();

         long start = System.currentTimeMillis();
         LOGGER.info("Launching AutoModpack...");

         Common.init();

         if (LOADER_MANAGER.getEnvironmentType() == LoaderService.EnvironmentType.SERVER) {
            Common.serverInit();
         } else {
             ModPackets.registerC2SPackets();
             new AudioManager(eventBus);
         }


         LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
    }

/^? if >1.20.5 {^/
   /^@EventBusSubscriber(modid = MOD_ID)
^//^?} else {^/
   @Mod.EventBusSubscriber(modid = MOD_ID)
/^?}^/
    public static class events {
        @SubscribeEvent
        public static void onCommandsRegister(RegisterCommandsEvent event) {
            Commands.register(event.getDispatcher());
        }
    }
}
*//*?}*/
