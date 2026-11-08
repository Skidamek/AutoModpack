package pl.skidam.automodpack.init;

import pl.skidam.automodpack_core.AutoModpack;

import pl.skidam.automodpack_core.Environment;

/*? if forge {*/
/*import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.modpack.Commands;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.screen.ScreenManager;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static pl.skidam.automodpack_core.Constants.*;

@Mod(AutoModpack.ID + "_mod")
public class ForgeInit {

	public ForgeInit() {
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

	@Mod.EventBusSubscriber(modid = AutoModpack.ID + "_mod")
	public static class events {
		@SubscribeEvent
		public static void onCommandsRegister(RegisterCommandsEvent event) {
			Commands.register(event.getDispatcher());
		}
	}
}
*//*?}*/
