package pl.skidam.automodpack_core.modpack.generation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure presentation data for the adjacent generation history and its patch notes. */
public final class GenerationHistorySummary {
	private GenerationHistorySummary() {}

	public static List<Entry> summarize(List<GenerationRecord> generations, List<GenerationPatchNoteHistory.Entry> patchNotesHistory) {
		Objects.requireNonNull(generations, "generations");
		Objects.requireNonNull(patchNotesHistory, "patch notes history");
		Map<String, String> notesByGeneration = new HashMap<>();
		for (GenerationPatchNoteHistory.Entry entry : patchNotesHistory) notesByGeneration.put(entry.generationId(), entry.patchNotes());
		List<Entry> summaries = new ArrayList<>(generations.size());
		for (int index = 0; index < generations.size(); index++) {
			GenerationRecord generation = Objects.requireNonNull(generations.get(index), "generation history entry");
			GenerationRecord predecessor = index == 0 ? null : generations.get(index - 1);
			String notes = notesByGeneration.getOrDefault(generation.metadata().generationId(), generation.metadata().patchNotes());
			summaries.add(new Entry(index + 1, generation.metadata().generationId(), generation.metadata().createdAt(), notes,
					GenerationDiff.between(predecessor == null ? null : predecessor.manifest(), generation.manifest())));
		}
		return List.copyOf(summaries);
	}

	public record Entry(int number, String generationId, Instant createdAt, String patchNotes, GenerationDiff diff) {
		public Entry {
			if (number < 1) throw new IllegalArgumentException("Generation number must be positive");
			generationId = Objects.requireNonNull(generationId, "generation ID");
			createdAt = Objects.requireNonNull(createdAt, "generation creation time");
			patchNotes = GenerationMetadata.validateNotes(patchNotes);
			diff = Objects.requireNonNull(diff, "generation diff");
		}
	}
}
