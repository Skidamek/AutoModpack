package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack_core.client.LaunchApplyState;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;

/**
 * The title-screen door to the changelog of a launch apply the player has not been shown. The receipt is read once and
 * consumed only when the player opens it, so quitting before clicking keeps it for the next launch.
 */
public final class LaunchApplyNotice {
	private static volatile Pending pending;
	private static volatile boolean read;

	private LaunchApplyNotice() {}

	private record Pending(String modpackId) {}

	/** True while this session has a receipt to announce; reading it consumes nothing. */
	public static boolean pending() {
		return notice() != null;
	}

	public static void open(Screen parent) {
		Pending notice = notice();
		if (notice == null) return;
		pending = null;
		try {
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			if (!LaunchApplyState.takeUnseenApply(storage, notice.modpackId())) return;
			GenerationHistoryController.open(parent, storage, notice.modpackId(), nameOf(storage, notice.modpackId()), () -> {});
		} catch (Exception e) {
			LOGGER.warn("The launch-apply history could not be opened", e);
		}
	}

	private static Pending notice() {
		if (!read) {
			synchronized (LaunchApplyNotice.class) {
				if (!read) {
					pending = readNotice();
					read = true;
				}
			}
		}
		return pending;
	}

	private static Pending readNotice() {
		try {
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			String modpackId = storage.selectedModpackId();
			if (modpackId == null || modpackId.isBlank()) return null;
			return LaunchApplyState.hasUnseenApply(storage, modpackId) ? new Pending(modpackId) : null;
		} catch (Exception e) {
			LOGGER.debug("Launch-apply notice is unavailable", e);
			return null;
		}
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
