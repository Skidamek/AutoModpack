package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.SelectionJsons;

class ClientSelectionStoreTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void roundTripsGroupsAndExclusionsWithoutDerivedGroups() throws Exception {
		Path path = temporaryDirectory.resolve("selection.json");
		ClientSelectionStore store = new ClientSelectionStore(path);
		SelectionIntent intent = new SelectionIntent(Set.of("optional", "stale-group"), Set.of("client"), Set.of("variant"));

		store.compareAndSet("abc1234", null, intent);

		assertEquals(intent, store.get("abc1234").orElseThrow());
		SelectionJsons.ClientSelectionStoreFields fields = ConfigTools.read(path, SelectionJsons.ClientSelectionStoreFields.class).orElseThrow();
		SelectionJsons.ClientSelectionStoreFields.ModpackSelection selection = fields.selections.get("abc1234");
		assertEquals(Set.of("optional", "stale-group"), selection.requestedGroups);
		assertEquals(Set.of("client"), selection.requestedTags);
		assertEquals(Set.of("variant"), selection.excludedGroups);
	}

	@Test
	void preservesStaleGroupIdsDuringCompareAndSet() throws Exception {
		ClientSelectionStore store = new ClientSelectionStore(temporaryDirectory.resolve("selection.json"));
		SelectionIntent intent = new SelectionIntent(Set.of("removed-group"));

		store.compareAndSet("abc1234", null, intent);

		assertEquals(Set.of("removed-group"), store.get("abc1234").orElseThrow().requestedGroups());
	}
}
