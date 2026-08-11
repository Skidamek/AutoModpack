package pl.skidam.automodpack_loader_core.screen;

import java.util.concurrent.CompletableFuture;

import pl.skidam.automodpack_core.modpack.generation.CatalogueSnapshot;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;

/**
 * Loads one historical catalogue on demand for the history screen.
 *
 * <p>
 * The screen only knows this small seam. Implementations own authentication, temporary files,
 * and connection lifetime, so opening history stays offline and cheap until a player asks for a
 * compacted entry's details.
 * </p>
 */
@FunctionalInterface
public interface HistoricalCatalogueLoader extends AutoCloseable {
	CompletableFuture<CatalogueSnapshot> load(GenerationHistoryIndex.Entry entry);

	@Override
	default void close() {}
}
