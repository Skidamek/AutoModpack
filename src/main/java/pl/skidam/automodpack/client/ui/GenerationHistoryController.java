package pl.skidam.automodpack.client.ui;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import net.minecraft.client.Minecraft;

import pl.skidam.automodpack_core.modpack.generation.CatalogueSnapshot;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryEntry;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
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
				GenerationHistoryIndex historyIndex = store.historyIndex(currentGenerationId).orElseGet(() -> GenerationHistoryIndex.fromHistory(modpackId,
						availableHistory.stream().map(record -> new GenerationHistoryEntry(record.manifest(), record.metadata())).toList()));
				HistoricalCatalogueLoader catalogueLoader = new LazyCatalogueLoader(storage, modpackId, availableHistory);
				ScreenManager.history(new HistoryViewRequest(historyIndex, availableHistory, modpackName, catalogueLoader, closed));
			} catch (Exception e) {
				Minecraft.getInstance().execute(closed);
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}

	private static final class LazyCatalogueLoader implements HistoricalCatalogueLoader {
		private final ClientStorage storage;
		private final String modpackId;
		private final Map<String, CatalogueSnapshot> localCatalogues;
		private StoredModpackConnection connection;
		private boolean closed;

		private LazyCatalogueLoader(ClientStorage storage, String modpackId, List<GenerationRecord> localHistory) {
			this.storage = Objects.requireNonNull(storage, "client storage");
			this.modpackId = Objects.requireNonNull(modpackId, "modpack ID");
			this.localCatalogues = localHistory.stream().collect(Collectors.toUnmodifiableMap(record -> record.metadata().generationId(), record -> CatalogueSnapshot.from(record.manifest())));
		}

		@Override
		public CompletableFuture<CatalogueSnapshot> load(GenerationHistoryIndex.Entry entry) {
			Objects.requireNonNull(entry, "history entry");
			CatalogueSnapshot local = localCatalogues.get(entry.generationId());
			if (local != null) return CompletableFuture.completedFuture(local);
			CompletableFuture<CatalogueSnapshot> result = new CompletableFuture<>();
			DownloadClient.NET_EXECUTOR.execute(() -> {
				try {
					StoredModpackConnection session = connection();
					session.downloadHistoricalCatalogue(entry).whenComplete((catalogue, failure) -> {
						if (failure == null) result.complete(catalogue);
						else result.completeExceptionally(failure);
					});
				} catch (Exception e) {
					result.completeExceptionally(e);
				}
			});
			return result;
		}

		@Override
		public synchronized void close() {
			closed = true;
			if (connection != null) connection.close();
		}

		private synchronized StoredModpackConnection connection() throws Exception {
			if (closed) throw new IllegalStateException("Historical catalogue loader is closed");
			if (connection == null) connection = StoredModpackConnection.open(storage, modpackId, true);
			return connection;
		}
	}
}
