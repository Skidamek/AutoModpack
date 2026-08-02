package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

class ClientContentHistoryTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void collapsesRevertedContentStates() throws Exception {
		Path historyFile = temporaryDirectory.resolve("history.json");
		Jsons.ModpackContentFields first = target("1", "a", Set.of("main"));
		Jsons.ModpackContentFields second = target("2", "b", Set.of("main", "optional"));
		Jsons.ModpackContentFields third = target("3", "c", Set.of("main"));
		Jsons.ModpackContentFields returned = target("4", "b", Set.of("main", "optional"));

		ClientContentHistory.record(historyFile, first);
		ClientContentHistory.record(historyFile, second);
		ClientContentHistory.record(historyFile, third);
		ClientContentHistory.record(historyFile, returned);

		ClientContentHistory.History history = ClientContentHistory.read(historyFile);
		assertEquals(2, history.entries().size());
		assertEquals("4".repeat(40), history.entries().get(1).generationId());
		assertEquals("b".repeat(40), history.entries().get(1).stateDigest());
	}

	@Test
	void rejectsNonAdjacentRepeatedContentStates() throws Exception {
		Path historyFile = temporaryDirectory.resolve("history.json");
		Jsons.ClientContentHistoryFields fields = new Jsons.ClientContentHistoryFields();
		fields.modpackId = "abc1234";
		fields.entries = new ArrayList<>();
		for (String state : new String[]{"a", "b", "a"}) {
			Jsons.ClientContentHistoryFields.EntryFields entry = new Jsons.ClientContentHistoryFields.EntryFields();
			entry.generationId = state.repeat(40);
			entry.stateDigest = state.repeat(40);
			entry.recordedAt = "2026-08-02T12:34:56Z";
			fields.entries.add(entry);
		}
		ConfigTools.writeAtomic(historyFile, fields);

		assertThrows(IOException.class, () -> ClientContentHistory.read(historyFile));
	}

	private static Jsons.ModpackContentFields target(String generation, String state, Set<String> groups) {
		Jsons.ModpackContentFields target = new Jsons.ModpackContentFields(Set.of());
		target.modpackId = "abc1234";
		target.targetGenerationId = generation.repeat(40);
		target.stateDigest = state.repeat(40);
		target.selectedGroups = groups;
		return target;
	}
}
