package pl.skidam.automodpack.client.ui;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
				Optional<GenerationHistoryIndex> storedHistory = store.historyIndex(currentGenerationId);
				try {
					StoredModpackConnection connection = StoredModpackConnection.open(storage, modpackId, true);
					ScreenManager.history(new HistoryViewRequest(connection.advertisedHistoryIndex(), availableHistory, modpackName, new ConnectedCatalogueLoader(connection), closed));
				} catch (IOException connectionFailure) {
					GenerationHistoryIndex offlineHistory = storedHistory.orElseGet(() -> localHistoryIndex(modpackId, availableHistory));
					ScreenManager.history(new HistoryViewRequest(offlineHistory, availableHistory, modpackName, new OfflineCatalogueLoader(connectionFailure), closed));
				}
			} catch (Exception e) {
				Minecraft.getInstance().execute(closed);
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}

	private static GenerationHistoryIndex localHistoryIndex(String modpackId, List<GenerationRecord> history) {
		return GenerationHistoryIndex.fromHistory(modpackId, history.stream().map(record -> new GenerationHistoryEntry(record.manifest(), record.metadata())).toList());
	}

	private static final class OfflineCatalogueLoader implements HistoricalCatalogueLoader {
		private final IOException failure;

		private OfflineCatalogueLoader(IOException failure) {
			this.failure = Objects.requireNonNull(failure, "connection failure");
		}

		@Override
		public CompletableFuture<CatalogueSnapshot> load(GenerationHistoryIndex.Entry entry) {
			Objects.requireNonNull(entry, "history entry");
			return CompletableFuture.failedFuture(failure);
		}

		@Override
		public void close() {}
	}

	private static final class ConnectedCatalogueLoader implements HistoricalCatalogueLoader {
		private final StoredModpackConnection connection;
		private volatile boolean closed;

		private ConnectedCatalogueLoader(StoredModpackConnection connection) {
			this.connection = Objects.requireNonNull(connection, "stored modpack connection");
		}

		@Override
		public CompletableFuture<CatalogueSnapshot> load(GenerationHistoryIndex.Entry entry) {
			Objects.requireNonNull(entry, "history entry");
			if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Historical catalogue loader is closed"));
			return connection.downloadHistoricalCatalogue(entry);
		}

		@Override
		public synchronized void close() {
			closed = true;
			connection.close();
		}
	}
}
