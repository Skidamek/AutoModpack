package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack_core.client.LaunchApplyState;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;

/**
 * The title-screen door to the changelog of a launch apply the player has not been shown. The launch-apply state owns the
 * receipt; this class only remembers whether it already asked and opens the history the player clicked.
 */
public final class LaunchApplyNotice {
	private static volatile LaunchApplyState.Notice notice;
	private static volatile boolean read;

	private LaunchApplyNotice() {}

	/** True while this session has a receipt to announce; reading it consumes nothing. */
	public static boolean pending() {
		return notice() != null;
	}

	public static void open(Screen parent) {
		LaunchApplyState.Notice pending = notice();
		if (pending == null) return;
		notice = null;
		try {
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			if (!LaunchApplyState.takeNotice(storage, pending)) return;
			GenerationHistoryController.open(parent, storage, pending.modpackId(), nameOf(storage, pending.modpackId()), () -> {});
		} catch (Exception e) {
			LOGGER.warn("The launch-apply history could not be opened", e);
		}
	}

	private static LaunchApplyState.Notice notice() {
		if (!read) {
			synchronized (LaunchApplyNotice.class) {
				if (!read) {
					try {
						notice = LaunchApplyState.unseenNotice(ClientStorage.open(GameDirectory.current()));
					} catch (Exception e) {
						LOGGER.debug("Launch-apply notice is unavailable", e);
					}
					read = true;
				}
			}
		}
		return notice;
	}

	private static String nameOf(ClientStorage storage, String modpackId) {
		try {
			InstalledModpackController.Pack pack = new InstalledModpackController(storage).installedPack(modpackId);
			if (pack != null) return pack.name();
		} catch (RuntimeException e) {
			LOGGER.debug("The launch-apply notice falls back to the pack id as its name", e);
		}
		return modpackId;
	}
}
