package pl.skidam.automodpack.client;

import net.minecraft.client.gui.components.toasts.SystemToast;

import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.VersionedToasts;
import pl.skidam.automodpack_core.client.SessionNotices;

/** Shows the session's pending notices to the player the moment a world exists to show them in. */
public final class SessionNoticeToasts {
	private SessionNoticeToasts() {}

	public static void announcePending() {
		for (SessionNotices.Notice notice : SessionNotices.take()) {
			var title = VersionedText.literal(notice.title());
			var description = VersionedText.literal(notice.message());
			VersionedToasts.add(new SystemToast(SystemToast.SystemToastId.PACK_LOAD_FAILURE, title, description), title, description);
		}
	}
}
