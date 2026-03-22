package pl.skidam.automodpack.init;

/*? if neoforge {*/
/*/^? if >1.20.5 {^/
import net.neoforged.fml.common.EventBusSubscriber;
/^?}^/
import pl.skidam.automodpack.modpack.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.lang.reflect.InvocationTargetException;

import static pl.skidam.automodpack_core.Constants.*;

@Mod(MOD_ID + "_mod")
public class NeoForgeInit {
    public NeoForgeInit(IEventBus eventBus) {
        preload = false;

        long start = System.currentTimeMillis();
        LOGGER.info("Launching AutoModpack...");

        Common.init();

        if (isDedicatedServer()) {
            Common.serverInit();
        } else {
            NeoForgeClientInit.init(eventBus);
        }


        LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
    }

    private static boolean isDedicatedServer() {
        try {
            return FMLEnvironment.class.getMethod("getDist").invoke(null).equals(Dist.DEDICATED_SERVER);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | NullPointerException ignored) {
        }

        try {
            return FMLLoader.class.getMethod("getDist").invoke(null).equals(Dist.DEDICATED_SERVER);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | NullPointerException ignored) {
        }

        try {
            Object current = FMLLoader.class.getMethod("getCurrent").invoke(null);
            return current.getClass().getMethod("getDist").invoke(current).equals(Dist.DEDICATED_SERVER);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | NullPointerException ignored) {
        }

        throw new RuntimeException("Can't determine dist!");
    }

/^? if >1.20.5 {^/
   @EventBusSubscriber(modid = MOD_ID + "_mod")
/^?} else {^/
   /^@Mod.EventBusSubscriber(modid = MOD_ID + "_mod")
^//^?}^/
    public static class events {
        @SubscribeEvent
        public static void onCommandsRegister(RegisterCommandsEvent event) {
            Commands.register(event.getDispatcher());
        }
    }
}
*//*?}*/
