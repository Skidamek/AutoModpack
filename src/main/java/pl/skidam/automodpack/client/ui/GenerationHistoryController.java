package pl.skidam.automodpack.client.ui;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;

import pl.skidam.automodpack_core.modpack.generation.CatalogueSnapshot;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_loader_core.client.StoredModpackConnection;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.HistoricalCatalogueLoader;
import pl.skidam.automodpack_loader_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/**
 * Builds the history projection and owns the lazy authenticated catalogue session used by it.
 * Screens receive only immutable display data plus the small loader seam.
 */
final class GenerationHistoryController {
	private GenerationHistoryController() {}

	static void open(ClientStorage storage, String modpackId, String currentGenerationId, String modpackName, Runnable closed) {
		Objects.requireNonNull(storage, "storage");
		Objects.requireNonNull(modpackId, "modpack ID");
		Objects.requireNonNull(currentGenerationId, "current generation ID");
		Objects.requireNonNull(closed, "closed callback");
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				ClientGenerationStore store = new ClientGenerationStore(storage);
				List<GenerationRecord> availableHistory = store.availableLineage(modpackId, currentGenerationId);
				GenerationHistoryIndex historyIndex = store.historyIndex(currentGenerationId)
						.orElseThrow(() -> new IOException("Generation history index is unavailable"));
				List<GenerationPatchNoteHistory.Entry> patchNotes = readPatchNotes(store, currentGenerationId, availableHistory);
				HistoricalCatalogueLoader catalogueLoader = new LazyCatalogueLoader(storage, modpackId);
				new ScreenManager().history(new HistoryViewRequest(historyIndex, availableHistory, modpackName, patchNotes, catalogueLoader, closed));
			} catch (Exception e) {
				Minecraft.getInstance().execute(closed);
				new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}

	private static List<GenerationPatchNoteHistory.Entry> readPatchNotes(ClientGenerationStore store, String generationId, List<GenerationRecord> availableHistory) {
		try {
			List<GenerationPatchNoteHistory.Entry> patchNotes = store.patchNotesHistory(generationId);
			return patchNotes.isEmpty() ? GenerationPatchNoteHistory.fromRecords(availableHistory) : patchNotes;
		} catch (IOException | RuntimeException ignored) {
			return GenerationPatchNoteHistory.fromRecords(availableHistory);
		}
	}

	private static final class LazyCatalogueLoader implements HistoricalCatalogueLoader {
		private final ClientStorage storage;
		private final String modpackId;
		private StoredModpackConnection connection;
		private boolean closed;

		private LazyCatalogueLoader(ClientStorage storage, String modpackId) {
			this.storage = storage;
			this.modpackId = modpackId;
		}

		@Override
		public CompletableFuture<CatalogueSnapshot> load(GenerationHistoryIndex.Entry entry) {
			Objects.requireNonNull(entry, "history entry");
			return CompletableFuture.supplyAsync(this::connection, DownloadClient.NET_EXECUTOR).thenCompose(session -> session.downloadHistoricalCatalogue(entry));
		}

		private synchronized StoredModpackConnection connection() {
			if (closed) throw new IllegalStateException("Historical catalogue loader is closed");
			if (connection == null) {
				try {
					connection = StoredModpackConnection.open(storage, modpackId, true);
				} catch (Exception e) {
					throw new IllegalStateException("Could not open the saved modpack connection", e);
				}
			}
			return connection;
		}

		@Override
		public synchronized void close() {
			closed = true;
			if (connection != null) {
				connection.close();
				connection = null;
			}
		}
	}
}
