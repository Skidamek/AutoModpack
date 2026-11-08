package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ui.versioned.VersionedServers;
import pl.skidam.automodpack_core.client.InstalledPacks;
import pl.skidam.automodpack_core.client.LaunchApplyState;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;

/**
 * The title-screen door to the changelog of a launch apply the player has not been shown. The launch-apply state owns the
 * receipt; this class only remembers whether it already asked and opens the history the player clicked.
 */
public final class LaunchApplyNotice {
	private static volatile String noticeModpackId;
	private static volatile boolean read;

	private LaunchApplyNotice() {}

	/** True while this session has a receipt to announce; reading it consumes nothing. */
	public static boolean pending() {
		return noticeModpackId() != null;
	}

	public static void open(Screen parent) {
		String pending = noticeModpackId();
		if (pending == null) return;
		noticeModpackId = null;
		try {
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			if (!LaunchApplyState.takeUnseenApply(storage, pending)) return;
			GenerationHistoryController.open(parent, storage, pending, InstalledPacks.nameOf(storage, pending, VersionedServers::entryName), () -> {});
		} catch (Exception e) {
			LOGGER.warn("The launch-apply history could not be opened", e);
		}
	}

	private static String noticeModpackId() {
		if (!read) {
			synchronized (LaunchApplyNotice.class) {
				if (!read) {
					try {
						noticeModpackId = LaunchApplyState.unseenNoticeModpackId(ClientStorage.open(GameDirectory.current()));
					} catch (Exception e) {
						LOGGER.debug("Launch-apply notice is unavailable", e);
					}
					read = true;
				}
			}
		}
		return noticeModpackId;
	}

}
