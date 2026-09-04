package pl.skidam.automodpack.client.ui.screen;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.JournalMirror;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/**
 * Builds the history projection from the pack's journal mirror, the client's replica of the server journal.
 * Screens receive only immutable display data; every journal entry already carries its own diff. The locally
 * active generation is the current one, and only its older locally restorable generations offer a restore.
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
				ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
				boolean activePack = active != null && active.modpackId.equals(modpackId);
				long currentSeq = -1;
				Set<Long> restorableSeqs = new TreeSet<>();
				if (activePack) {
					ClientGenerationStore generations = new ClientGenerationStore(storage);
					for (JournalEntry entry : journal) {
						if (entry.contentToken().equals(active.contentToken)) currentSeq = entry.seq();
						else if (generations.locallyRestorable(modpackId, entry)) restorableSeqs.add(entry.seq());
					}
				}
				Consumer<JournalEntry> restore = activePack ? entry -> GenerationRollback.start(storage, modpackId, entry, modpackName, closed) : null;
				ScreenManager.history(new HistoryViewRequest(journal, currentSeq, modpackName, closed, restorableSeqs, restore));
			} catch (Exception e) {
				Minecraft.getInstance().execute(closed);
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}
}
