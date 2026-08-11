package pl.skidam.automodpack_loader_core.screen;

import java.util.List;
import java.util.Objects;

import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;

/** All immutable inputs needed to present generation history. */
public record HistoryViewRequest(GenerationHistoryIndex historyIndex, List<GenerationRecord> availableHistory, String modpackName,
		List<GenerationPatchNoteHistory.Entry> patchNotesHistory, HistoricalCatalogueLoader catalogueLoader, Runnable closed) {
	public HistoryViewRequest {
		Objects.requireNonNull(historyIndex, "history index");
		availableHistory = List.copyOf(Objects.requireNonNull(availableHistory, "available history"));
		Objects.requireNonNull(modpackName, "modpack name");
		patchNotesHistory = List.copyOf(Objects.requireNonNull(patchNotesHistory, "patch notes history"));
		Objects.requireNonNull(catalogueLoader, "catalogue loader");
		Objects.requireNonNull(closed, "closed callback");
	}
}
