package pl.skidam.automodpack.client;

import net.minecraft.client.gui.components.toasts.SystemToast;

import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.VersionedToasts;
import pl.skidam.automodpack_core.client.ClientHostState;

/** Announces a session hosting failure to the player the moment a world exists to show it in. */
public final class ClientHostMessage {
	private ClientHostMessage() {}

	public static void announcePendingFailure() {
		String failure = ClientHostState.takeFailure();
		if (failure == null) return;
		var title = VersionedText.literal("AutoModpack hosting failed");
		var description = VersionedText.literal(failure);
		VersionedToasts.add(new SystemToast(SystemToast.SystemToastId.PACK_LOAD_FAILURE, title, description), title, description);
	}
}
