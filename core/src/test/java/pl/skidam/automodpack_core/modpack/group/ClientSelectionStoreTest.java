package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

class ClientSelectionStoreTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void roundTripsTagsGroupsAndExclusionsWithoutDerivedGroups() throws Exception {
		Path path = temporaryDirectory.resolve("selection.json");
		ClientSelectionStore store = new ClientSelectionStore(path);
		SelectionIntent intent = new SelectionIntent(Set.of("visuals", "stale-tag"), Set.of("optional", "stale-group"), Set.of("variant"));

		store.compareAndSet("abc1234", null, intent);

		assertEquals(intent, store.get("abc1234").orElseThrow());
		Jsons.ClientSelectionStoreFields fields = ConfigTools.read(path, Jsons.ClientSelectionStoreFields.class).orElseThrow();
		Jsons.ClientSelectionStoreFields.ModpackSelection selection = fields.selections.get("abc1234");
		assertEquals(Set.of("visuals", "stale-tag"), selection.requestedTags);
		assertEquals(Set.of("optional", "stale-group"), selection.requestedGroups);
		assertEquals(Set.of("variant"), selection.excludedGroups);
	}

	@Test
	void preservesStaleTagAndGroupIdsDuringCompareAndSet() throws Exception {
		ClientSelectionStore store = new ClientSelectionStore(temporaryDirectory.resolve("selection.json"));
		SelectionIntent intent = new SelectionIntent(Set.of("removed-tag"), Set.of("removed-group"));

		store.compareAndSet("abc1234", null, intent);

		assertEquals(Set.of("removed-tag"), store.get("abc1234").orElseThrow().requestedTags());
		assertEquals(Set.of("removed-group"), store.get("abc1234").orElseThrow().requestedGroups());
	}
}
