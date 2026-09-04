package pl.skidam.automodpack_loader_core.screen;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import pl.skidam.automodpack_core.modpack.generation.JournalEntry;

/**
 * All immutable inputs needed to present modpack history. A non-null {@code restore} enables the per-generation
 * restore affordance for exactly the sequences listed as locally restorable; views without it stay read-only.
 */
public record HistoryViewRequest(List<JournalEntry> journal, long currentSeq, String modpackName, Runnable closed, Set<Long> restorableSeqs,
		Consumer<JournalEntry> restore) {
	public HistoryViewRequest {
		journal = List.copyOf(Objects.requireNonNull(journal, "journal"));
		Objects.requireNonNull(modpackName, "modpack name");
		Objects.requireNonNull(closed, "closed callback");
		restorableSeqs = restorableSeqs == null ? Set.of() : Set.copyOf(restorableSeqs);
	}

	public HistoryViewRequest(List<JournalEntry> journal, long currentSeq, String modpackName, Runnable closed) {
		this(journal, currentSeq, modpackName, closed, Set.of(), null);
	}
}
