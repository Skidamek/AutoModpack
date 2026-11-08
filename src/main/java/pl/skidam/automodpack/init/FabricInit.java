package pl.skidam.automodpack.init;

import pl.skidam.automodpack_core.Environment;

/*? if fabric {*/
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.screen.ScreenManager;

import static pl.skidam.automodpack_core.Constants.*;

public class FabricInit {

	public static void onInitialize() {

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
}
/*?}*/
