package pl.skidam.automodpack_core.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChangeSetTest {
	private static final String OLD_HASH = "2222222222222222222222222222222222222222";
	private static final String NEW_HASH = "1111111111111111111111111111111111111111";

	@Test
	void foldsLogicalPathWithoutDroppingPhysicalOccurrencesOrReferences() {
		ChangeSet changes = ChangeSet.of(List.of(
				new ChangeSet.Change("config/shared.json", ChangeSet.Kind.REMOVED,
						List.of(new ChangeSet.Occurrence("PROJECTION", "config/shared.json", 4, OLD_HASH, null, List.of("https://old.example")))),
				new ChangeSet.Change("config/shared.json", ChangeSet.Kind.ADDED,
						List.of(new ChangeSet.Occurrence("GAME_DIR", "config/shared.json", 8, null, NEW_HASH, List.of("https://new.example"))))));

		assertEquals(ChangeSet.Kind.MODIFIED, changes.changes().get(0).kind());
		assertEquals(List.of("GAME_DIR", "PROJECTION"), changes.changes().get(0).occurrences().stream().map(ChangeSet.Occurrence::location).toList());
		assertEquals(List.of("https://new.example", "https://old.example"), changes.changes().get(0).occurrences().stream().flatMap(occurrence -> occurrence.references().stream()).toList());
		assertEquals(new ChangeSet.Summary(0, 1, 0, 0, 0, 0, 0), changes.summary());
	}

	@Test
	void addsReferencesWithoutMutatingTheOriginalSet() {
		ChangeSet original = ChangeSet.of(new ChangeSet.Change("mods/example.jar", ChangeSet.Kind.MODIFIED,
				List.of(new ChangeSet.Occurrence("GAME_DIR", "mods/example.jar", 12, OLD_HASH, NEW_HASH))));
		ChangeSet enriched = original.withReferences((location, path) -> List.of("https://example.invalid", "https://example.invalid"));

		assertEquals(List.of(), original.changes().get(0).occurrences().get(0).references());
		assertEquals(List.of("https://example.invalid"), enriched.changes().get(0).occurrences().get(0).references());
		assertThrows(UnsupportedOperationException.class, () -> enriched.changes().add(null));
	}
}
