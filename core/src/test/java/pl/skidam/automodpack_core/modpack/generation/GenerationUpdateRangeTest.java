package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class GenerationUpdateRangeTest {
	private static final String FIRST = "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8";
	private static final String SECOND = "e9d71f5ee7c92d6dc9e92ffdad17b8bd49418f98";
	private static final String THIRD = "84a516841ba77a5b4648de2cd0dfcb30ea46dbb4";

	@Test
	void oneGenerationUsesOnlyItsOwnNotes() {
		GenerationUpdateRange range = GenerationUpdateRange.between(history("Release", ""), SECOND, THIRD);

		assertEquals(1, range.generations().size());
		assertTrue(range.complete());
		assertFalse(range.featuredNotes().isPresent());
	}

	@Test
	void skippedHotPatchUsesNewestNonEmptyNotesInAppliedRange() {
		GenerationUpdateRange range = GenerationUpdateRange.between(history("Feature release", ""), FIRST, THIRD);

		assertEquals(List.of(SECOND, THIRD), range.generations().stream().map(GenerationPatchNoteHistory.Entry::generationId).toList());
		assertTrue(range.spansMultipleGenerations());
		assertEquals("Feature release", range.featuredNotes().orElseThrow().patchNotes());
		assertEquals(SECOND, range.featuredNotes().orElseThrow().generationId());
	}

	@Test
	void firstInstallationShowsOnlyTheCurrentGeneration() {
		GenerationUpdateRange range = GenerationUpdateRange.between(history("Old release", "Current release"), null, THIRD);

		assertEquals(List.of(THIRD), range.generations().stream().map(GenerationPatchNoteHistory.Entry::generationId).toList());
		assertEquals("Current release", range.featuredNotes().orElseThrow().patchNotes());
	}

	@Test
	void unknownInstalledGenerationMarksTheRangeIncomplete() {
		GenerationUpdateRange range = GenerationUpdateRange.between(history("Old release", "Current release"), "3c363836cf4e16666669a25da280a1865c2d2874", THIRD);

		assertFalse(range.complete());
		assertEquals(List.of(THIRD), range.generations().stream().map(GenerationPatchNoteHistory.Entry::generationId).toList());
	}

	private static List<GenerationPatchNoteHistory.Entry> history(String secondNotes, String thirdNotes) {
		return List.of(entry(FIRST, "", "First"), entry(SECOND, FIRST, secondNotes), entry(THIRD, SECOND, thirdNotes));
	}

	private static GenerationPatchNoteHistory.Entry entry(String generationId, String parentGenerationId, String notes) {
		return new GenerationPatchNoteHistory.Entry(GenerationMetadata.CURRENT_SCHEMA_VERSION, generationId, parentGenerationId, Instant.parse("2026-01-01T00:00:00Z"), notes,
				GenerationIdentity.patchNotesDigest(notes));
	}
}
