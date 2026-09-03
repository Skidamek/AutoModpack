package pl.skidam.automodpack_loader_core.screen;

import java.util.List;
import java.util.Objects;

import pl.skidam.automodpack_core.modpack.generation.JournalEntry;

/** All immutable inputs needed to present modpack history. */
public record HistoryViewRequest(List<JournalEntry> journal, long currentSeq, String modpackName, Runnable closed) {
	public HistoryViewRequest {
		journal = List.copyOf(Objects.requireNonNull(journal, "journal"));
		Objects.requireNonNull(modpackName, "modpack name");
		Objects.requireNonNull(closed, "closed callback");
	}
}
