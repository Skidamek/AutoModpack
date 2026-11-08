package pl.skidam.automodpack_core.client;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.update.ClientStorage;

/**
 * The durable per-pack launch-apply state: whether an automatic launch apply may write jars the platform lookup did not
 * verify, and whether an apply the player has not been shown is still to be announced. Both live on the pack's
 * connection record, keyed by modpack identity; this class is the only place that gives them meaning.
 */
public final class LaunchApplyState {
	private static final Logger LOGGER = LogManager.getLogger(LaunchApplyState.class);

	private LaunchApplyState() {}

	/** Stored absence reads as refused, and an unreadable record refuses too: an automatic apply never guesses a yes. */
	public static boolean allowsUnverifiedLaunchWrites(ClientStorage storage, String modpackId) {
		try {
			return ConnectionStore.read(storage, modpackId).allowUnverifiedLaunchWrites == Boolean.TRUE;
		} catch (IOException e) {
			LOGGER.warn("The launch-consent record of {} is unreadable; unverified jars stay refused for launch applies", modpackId, e);
			return false;
		}
	}

	/** The player's answer, written where the dialog was confirmed or where the pack settings changed it. */
	public static void setAllowsUnverifiedLaunchWrites(ClientStorage storage, String modpackId, boolean allowed) {
		try {
			ModpackId.requireValid(modpackId);
			ConnectionStore.update(storage, modpackId, fields -> fields.allowUnverifiedLaunchWrites = allowed ? Boolean.TRUE : Boolean.FALSE);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("The launch-consent record of {} could not be saved", modpackId, e);
		}
	}

	/** Marks that a launch apply changed this pack and the player has not seen its history yet. */
	public static void recordUnseenApply(ClientStorage storage, String modpackId) {
		try {
			ModpackId.requireValid(modpackId);
			ConnectionStore.update(storage, modpackId, fields -> fields.unseenLaunchApply = Boolean.TRUE);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("The launch-apply receipt of {} could not be saved", modpackId, e);
		}
	}

	/** The selected pack whose launch apply the player has not been shown, or null; reading does not consume it. */
	public static String unseenNoticeModpackId(ClientStorage storage) {
		try {
			String modpackId = storage.selectedModpackId();
			if (modpackId == null || modpackId.isBlank()) return null;
			return hasUnseenApply(storage, modpackId) ? modpackId : null;
		} catch (IOException e) {
			LOGGER.debug("The launch-apply notice is unavailable", e);
			return null;
		}
	}

	/** Whether the receipt is set, without consuming it. */
	public static boolean hasUnseenApply(ClientStorage storage, String modpackId) throws IOException {
		return ConnectionStore.read(storage, modpackId).unseenLaunchApply == Boolean.TRUE;
	}

	/** Consumes the receipt, so the notice fires once; false when there was none or it already went. */
	public static boolean takeUnseenApply(ClientStorage storage, String modpackId) throws IOException {
		ConnectionJsons.ConnectionRecordFields fields = ConnectionStore.read(storage, modpackId);
		if (fields.unseenLaunchApply != Boolean.TRUE) return false;
		ConnectionStore.update(storage, modpackId, record -> record.unseenLaunchApply = null);
		return true;
	}
}
