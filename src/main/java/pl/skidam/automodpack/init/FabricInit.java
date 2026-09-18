package pl.skidam.automodpack.init;

/*? if fabric {*/
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.screen.ScreenManager;

import static pl.skidam.automodpack_core.Constants.*;

public class FabricInit {

	public static void onInitialize() {

		preload = false;

		long start = System.currentTimeMillis();
		LOGGER.info("Launching AutoModpack...");

		Common.init();

		if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER) {
			Common.serverInit();
		} else {
			ScreenManager.install(new ScreenImpl());
			ModPackets.registerC2SPackets();
			new AudioManager();
		}

		LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
	}
}
/*?}*/
