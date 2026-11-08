package pl.skidam.automodpack_core.client;

import java.awt.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.client.compat.crashassistant.ProcessSignalIO;
import pl.skidam.automodpack_core.screen.ScreenManager;

public class ReLauncher {
	private static final Logger LOGGER = LogManager.getLogger(ReLauncher.class);

	private final String updateMessage;
	private final UpdateType updateType;
	private final Changelogs changelogs;

	public ReLauncher(UpdateType updateType) {
		this(updateType, null, "Successfully updated AutoModpack!");
	}

	public ReLauncher(UpdateType updateType, Changelogs changelogs) {
		this(updateType, changelogs, "Successfully updated the modpack!");
	}

	public ReLauncher(UpdateType updateType, Changelogs changelogs, String updateMessage) {
		this.updateType = updateType;
		this.changelogs = changelogs;
		this.updateMessage = updateMessage;
	}

	public final void restart(boolean shutdownInPreload, Runnable... callbacks) {
		if (Environment.isPrelaunching() && !shutdownInPreload) {
			runCallbacks(callbacks);
			return;
		}

		boolean isClient = Environment.current().isClient();
		boolean isHeadless = GraphicsEnvironment.isHeadless();

		if (isClient) {
			handleClientRestart(callbacks, isHeadless);
		} else {
			handleServerRestart(callbacks);
		}
	}

	private void handleClientRestart(Runnable[] callbacks, boolean isHeadless) {
		if (updateType != null && ScreenManager.hasScreen()) {
			ScreenManager.restart(updateType, changelogs);
		} else if (Environment.isPrelaunching()) {
			ProcessSignalIO.post("normal_stop"); // let crash assistant know
			if (isHeadless) LOGGER.info("Please restart the game to apply updates!");
			else ScreenManager.preloadRestart(updateMessage);
			runCallbacks(callbacks);
			System.exit(0);
		}

		runCallbacks(callbacks);
	}

	private void handleServerRestart(Runnable[] callbacks) {
		LOGGER.info("Please restart the server to apply updates!");
		runCallbacks(callbacks);
		System.exit(0);
	}

	private void runCallbacks(Runnable[] callbacks) {
		for (Runnable callback : callbacks) {
			try {
				callback.run();
			} catch (Exception e) {
				LOGGER.error("Restart callback failed", e);
			}
		}
	}
}
