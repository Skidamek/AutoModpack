package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;

class GenerationHistorySummaryTest {
	@Test
	void attachesGenerationNotesAndDiffsToAdjacentPredecessors() {
		GenerationRecord first = GenerationRecord.create(manifest("old", "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8"), null,
				Instant.parse("2026-01-01T00:00:00Z"), "First generation");
		GenerationRecord second = GenerationRecord.create(manifest("new", "e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98"), first,
				Instant.parse("2026-01-02T00:00:00Z"), "Second generation");

		List<GenerationHistorySummary.Entry> summaries = GenerationHistorySummary.summarize(List.of(first, second), GenerationPatchNoteHistory.fromRecords(List.of(first, second)));

		assertEquals(List.of("First generation", "Second generation"), summaries.stream().map(GenerationHistorySummary.Entry::patchNotes).toList());
		assertEquals(1, summaries.get(0).diff().summary().addedFiles());
		assertEquals(1, summaries.get(1).diff().summary().modifiedFiles());
		assertEquals(second.metadata().generationId(), summaries.get(1).generationId());
	}

	@Test
	void carriesTheLatestNonEmptyNoteAcrossSkippedAndEmptyGenerations() {
		GenerationRecord first = GenerationRecord.create(manifest("first", "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8"), null,
				Instant.parse("2026-01-01T00:00:00Z"), "First generation");
		GenerationRecord skipped = GenerationRecord.create(manifest("skipped", "e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98"), first,
				Instant.parse("2026-01-02T00:00:00Z"), "Latest installed notes");
		GenerationRecord current = GenerationRecord.create(manifest("current", "84a516841ba77a5b4648de2cd0dfcb30ea46dbb4"), skipped,
				Instant.parse("2026-01-03T00:00:00Z"), "");

		List<GenerationHistorySummary.Entry> summaries = GenerationHistorySummary.summarize(List.of(first, current), GenerationPatchNoteHistory.fromRecords(List.of(first, skipped, current)));

		assertEquals(List.of("First generation", "Latest installed notes"), summaries.stream().map(GenerationHistorySummary.Entry::patchNotes).toList());
	}

	private static GroupManifest manifest(String name, String hash) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.modpackName = name;
		var group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.files = new LinkedHashMap<>(Map.of("mods/test.jar", new ModpackJsons.CompleteModpackContentFields.GroupFileFields("1", "mod", false, false, hash, null)));
		fields.groups = Map.of("main", group);
		return GroupManifestValidator.validate(fields);
	}
}
