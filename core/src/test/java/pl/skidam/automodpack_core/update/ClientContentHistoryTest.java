package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;

class ClientContentHistoryTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void serializesFriendlySelectionAndPatchNotes() throws Exception {
		Path historyFile = temporaryDirectory.resolve("history.json");
		Jsons.ModpackContentFields target = target("a", Set.of("main"));
		target.modpackName = "Example pack";
		ResolvedSelection selection = new ResolvedSelection(new SelectionIntent(Set.of("visuals"), Set.of("requested")), Set.of("main"), Set.of());

		ClientContentHistory.record(historyFile, target, selection, "Added visual improvements");

		ClientContentHistory.Entry entry = ClientContentHistory.read(historyFile).entries().get(0);
		assertNotEquals(target.stateDigest, entry.stateDigest());
		assertEquals(40, entry.stateDigest().length());
		assertEquals("Added visual improvements", entry.patchNotes());
		assertEquals(Set.of("visuals"), entry.selectedTags());
		assertEquals(Set.of("main"), entry.selectedGroups());
		assertEquals("0 files, 0 B", entry.fileSummary());
	}

	@Test
	void collapsesRevertedContentStates() throws Exception {
		Path historyFile = temporaryDirectory.resolve("history.json");
		Jsons.ModpackContentFields first = target("a", Set.of("main"));
		Jsons.ModpackContentFields second = target("b", Set.of("main", "optional"));
		Jsons.ModpackContentFields third = target("c", Set.of("main"));
		Jsons.ModpackContentFields returned = target("b", Set.of("main", "optional"));

		ClientContentHistory.record(historyFile, first, null, "first");
		ClientContentHistory.record(historyFile, second, null, "second");
		ClientContentHistory.record(historyFile, third, null, "third");
		ClientContentHistory.record(historyFile, returned, null, "returned");

		ClientContentHistory.History history = ClientContentHistory.read(historyFile);
		assertEquals(2, history.entries().size());
		assertNotEquals(history.entries().get(0).stateDigest(), history.entries().get(1).stateDigest());
		assertEquals("returned", history.entries().get(1).patchNotes());
	}

	@Test
	void ignoresCompleteCatalogueChangesWhenSelectedFlatContentIsUnchanged() throws Exception {
		Path historyFile = temporaryDirectory.resolve("history.json");
		ClientContentHistory.record(historyFile, target("a", Set.of("main")), null, "first");
		ClientContentHistory.record(historyFile, target("b", Set.of("main")), null, "second");

		ClientContentHistory.History history = ClientContentHistory.read(historyFile);
		assertEquals(1, history.entries().size());
		assertEquals("second", history.entries().get(0).patchNotes());
	}

	@Test
	void keepsDifferentSelectedFlatContentStatesSeparateWithinOneCatalogue() throws Exception {
		Path historyFile = temporaryDirectory.resolve("history.json");
		ClientContentHistory.record(historyFile, target("a", Set.of("main")), null, "main");
		ClientContentHistory.record(historyFile, target("a", Set.of("optional")), null, "optional");

		ClientContentHistory.History history = ClientContentHistory.read(historyFile);
		assertEquals(2, history.entries().size());
		assertNotEquals(history.entries().get(0).stateDigest(), history.entries().get(1).stateDigest());
	}

	@Test
	void rejectsNonAdjacentRepeatedContentStates() throws Exception {
		Path historyFile = temporaryDirectory.resolve("history.json");
		Jsons.ClientContentHistoryFields fields = new Jsons.ClientContentHistoryFields();
		fields.modpackId = "abc1234";
		fields.entries = new ArrayList<>();
		for (String state : new String[]{"a", "b", "a"}) {
			Jsons.ClientContentHistoryFields.EntryFields entry = new Jsons.ClientContentHistoryFields.EntryFields();
			entry.stateDigest = state.repeat(40);
			entry.recordedAt = "2026-08-02T12:34:56Z";
			fields.entries.add(entry);
		}
		ConfigTools.writeAtomic(historyFile, fields);

		assertThrows(IOException.class, () -> ClientContentHistory.read(historyFile));
	}

	private static Jsons.ModpackContentFields target(String state, Set<String> groups) {
		Jsons.ModpackContentFields target = new Jsons.ModpackContentFields(Set.of());
		target.modpackId = "abc1234";
		target.targetGenerationId = "1".repeat(40);
		target.stateDigest = state.repeat(40);
		target.selectedGroups = groups;
		return target;
	}
}
