package pl.skidam.automodpack_core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** What this session must tell the player once a world exists to show it in, such as failed hosting or an update that keeps booting unfinished. */
public final class SessionNotices {
	public record Notice(String title, String message) {}

	private static final Queue<Notice> PENDING = new ConcurrentLinkedQueue<>();

	private SessionNotices() {}

	public static void post(String title, String message) {
		PENDING.add(new Notice(title, message));
	}

	/** The pending notices; taking them clears them, so the player is told each once per session. */
	public static List<Notice> take() {
		List<Notice> taken = new ArrayList<>();
		for (Notice notice; (notice = PENDING.poll()) != null;) taken.add(notice);
		return taken;
	}
}
