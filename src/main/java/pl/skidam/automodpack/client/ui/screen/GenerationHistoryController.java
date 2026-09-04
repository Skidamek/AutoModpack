package pl.skidam.automodpack.client.ui.screen;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import net.minecraft.client.Minecraft;

import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.JournalMirror;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/**
 * Builds the history projection from the pack's journal mirror, the client's replica of the server journal.
 * Screens receive only immutable display data; every journal entry already carries its own diff.
 */
final class GenerationHistoryController {
	private GenerationHistoryController() {}

	static void open(ClientStorage storage, String modpackId, String modpackName, Runnable closed) {
		Objects.requireNonNull(storage, "storage");
		Objects.requireNonNull(modpackId, "modpack id");
		Objects.requireNonNull(closed, "closed callback");
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				JournalMirror mirror = new JournalMirror(storage);
				if (!mirror.exists(modpackId)) throw new IOException("Installed modpack journal is missing: " + modpackId);
				List<JournalEntry> journal = mirror.entries(modpackId);
				long currentSeq = journal.isEmpty() ? 0 : journal.get(journal.size() - 1).seq();
				ScreenManager.history(new HistoryViewRequest(journal, currentSeq, modpackName, closed));
			} catch (Exception e) {
				Minecraft.getInstance().execute(closed);
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}
}
