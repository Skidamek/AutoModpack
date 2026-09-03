package pl.skidam.automodpack.client.ui.screen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.client.Minecraft;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/**
 * Builds the history projection from the stored journal tail of one installed pack.
 * Screens receive only immutable display data; every journal entry already carries its own diff.
 */
final class GenerationHistoryController {
	private GenerationHistoryController() {}

	static void open(ClientStorage storage, String contentToken, String modpackName, Runnable closed) {
		Objects.requireNonNull(storage, "storage");
		Objects.requireNonNull(contentToken, "content token");
		Objects.requireNonNull(closed, "closed callback");
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				GenerationJsons.HeadDocumentFields fields = new ClientGenerationStore(storage).readFields(contentToken)
						.orElseThrow(() -> new IOException("Installed modpack record is missing: " + contentToken));
				List<JournalEntry> journal = new ArrayList<>();
				for (GenerationJsons.JournalEntryFields entry : fields.journal == null ? List.<GenerationJsons.JournalEntryFields>of() : fields.journal)
					journal.add(JournalEntry.fromFields(entry));
				ScreenManager.history(new HistoryViewRequest(journal, fields.journalHead, modpackName, closed));
			} catch (Exception e) {
				Minecraft.getInstance().execute(closed);
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}
}
