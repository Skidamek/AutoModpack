package pl.skidam.automodpack.init;

import pl.skidam.automodpack_core.AutoModpack;

import pl.skidam.automodpack_core.Environment;

/*? if neoforge {*/
/*/^? if >1.20.5 {^/
import net.neoforged.fml.common.EventBusSubscriber;
/^?}^/
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.modpack.Commands;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.screen.ScreenManager;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static pl.skidam.automodpack_core.Constants.*;

@Mod(AutoModpack.ID + "_mod")
public class NeoForgeInit {
	public NeoForgeInit() {
		Environment.current().markGameStarted();

		long start = System.currentTimeMillis();
		LOGGER.debug("Launching AutoModpack...");

		Common.init();

		if (Environment.current().isServer()) {
			Common.serverInit();
		} else {
			ScreenManager.install(new ScreenImpl());
			ModPackets.registerC2SPackets();
			new AudioManager();
		}


		LOGGER.debug("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
	}

/^? if >1.20.5 {^/
@EventBusSubscriber(modid = AutoModpack.ID + "_mod")
/^?} else {^/
/^@Mod.EventBusSubscriber(modid = AutoModpack.ID + "_mod")
^//^?}^/
	public static class events {
		@SubscribeEvent
		public static void onCommandsRegister(RegisterCommandsEvent event) {
			Commands.register(event.getDispatcher());
		}
	}
}
*//*?}*/
