package pl.skidam.automodpack_core.modpack.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.SelectionJsons;

class ClientSelectionStoreTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void roundTripsEnabledAndDisabledWithoutDerivedGroups() throws Exception {
		Path path = temporaryDirectory.resolve("selection.json");
		ClientSelectionStore store = new ClientSelectionStore(path);
		SelectionIntent intent = new SelectionIntent(Set.of("optional", "stale-group"), Set.of("variant"), null);

		store.put("abc1234", intent);

		assertEquals(intent, store.get("abc1234").orElseThrow());
		SelectionJsons.ClientSelectionStoreFields fields = ConfigTools.read(path, SelectionJsons.ClientSelectionStoreFields.class).orElseThrow();
		SelectionJsons.SelectionFields selection = fields.selections.get("abc1234");
		assertEquals(List.of("optional", "stale-group"), selection.enabled);
		assertEquals(List.of("variant"), selection.disabled);
	}

	@Test
	void preservesStaleGroupIdsWhenSaved() throws Exception {
		ClientSelectionStore store = new ClientSelectionStore(temporaryDirectory.resolve("selection.json"));
		SelectionIntent intent = SelectionIntent.ofEnabled(Set.of("removed-group"));

		store.put("abc1234", intent);

		assertEquals(Set.of("removed-group"), store.get("abc1234").orElseThrow().enabled());
	}

	@Test
	void savingPersistsTheResolutionPlatformAndTheLastConsentWins() throws Exception {
		ClientSelectionStore store = new ClientSelectionStore(temporaryDirectory.resolve("selection.json"));
		SelectionIntent intent = new SelectionIntent(Set.of("optional"), Set.of(), ClientPlatform.LINUX);

		store.put("abc1234", intent);

		// The stored platform is what later boots re-resolve under; equality still ignores it, so a
		// platform-less expected intent still matches the stored selection with one.
		assertEquals(ClientPlatform.LINUX, store.get("abc1234").orElseThrow().platform());
		store.put("abc1234", new SelectionIntent(Set.of("optional"), Set.of(), ClientPlatform.WINDOWS));
		assertEquals(ClientPlatform.WINDOWS, store.get("abc1234").orElseThrow().platform());
	}

	@Test
	void corruptStoreContentIsSetAsideAndReadsAsNoSelection() throws Exception {
		Path path = temporaryDirectory.resolve("selection.json");
		Files.writeString(path, "{ not json", StandardCharsets.UTF_8);

		ClientSelectionStore store = new ClientSelectionStore(path);

		assertTrue(store.get("abc1234").isEmpty());
		assertFalse(Files.exists(path));
		try (var leftovers = Files.list(temporaryDirectory)) {
			assertTrue(leftovers.anyMatch(entry -> entry.getFileName().toString().startsWith("selection.json.corrupt-")));
		}
	}
}
