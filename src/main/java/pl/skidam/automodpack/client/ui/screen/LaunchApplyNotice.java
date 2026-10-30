package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;

/** Title-screen receipt for a launch apply the player has not been shown yet. Showing it once consumes the durable pointer. */
public final class LaunchApplyNotice {
	private static volatile Held held;
	private static volatile boolean taken;

	private LaunchApplyNotice() {}

	private record Held(String modpackId, String name) {}

	/** True when this session still has a launch-apply receipt to show; the first call consumes the durable pointer. */
	public static boolean pending() {
		return peek() != null;
	}

	private static Held peek() {
		if (held != null) return held;
		if (taken) return null;
		try {
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			String modpackId = storage.selectedModpackId();
			if (modpackId == null || modpackId.isBlank()) {
				taken = true;
				return null;
			}
			Long seq = ConnectionStore.takeUnseenLaunchApplySeq(storage, modpackId);
			taken = true;
			if (seq == null) return null;
			String name = modpackId;
			try {
				InstalledModpackController.Pack pack = new InstalledModpackController(storage).installedPack(modpackId);
				if (pack != null) name = pack.name();
			} catch (RuntimeException ignored) {
			}
			held = new Held(modpackId, name);
			return held;
		} catch (Exception e) {
			LOGGER.debug("Launch-apply notice is unavailable", e);
			return null;
		}
	}

	public static void open(Screen parent) {
		Held notice = peek();
		if (notice == null) return;
		held = null;
		try {
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			GenerationHistoryController.open(parent, storage, notice.modpackId(), notice.name(), () -> {});
		} catch (Exception e) {
			LOGGER.warn("The launch-apply history could not be opened", e);
		}
	}
}
